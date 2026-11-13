package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.text.LocalizedText
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class SettingsNamespaceView(
    val ns: String,
    val schema: SchemaNode,
    val value: JsonObject,
    val revision: Long,
    val base: JsonObject?,
    val user: JsonObject?,
    val autoGenerate: Boolean,
    val secrets: PersistentList<SettingsSecretSlot>,
    val writable: Boolean,
) {
    companion object {
        internal fun parse(value: JsonElement, writable: Boolean): SettingsNamespaceView {
            val raw = value.objectValue()
            val slots =
                (raw["secrets"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { item ->
                        val slot = item as? JsonObject ?: return@mapNotNull null
                        SettingsSecretSlot(
                            (slot["path"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                            slot.boolean("set") == true,
                        )
                    }
                    .toPersistentList()
            return SettingsNamespaceView(
                raw.requiredString("ns"),
                SchemaNode.parse(raw["schema"]?.toToolPayload()),
                raw["value"] as? JsonObject ?: JsonObject(emptyMap()),
                raw.requiredLong("revision"),
                raw["base"] as? JsonObject,
                raw["user"] as? JsonObject,
                raw.boolean("autoGenerate") != false,
                slots,
                writable && raw.boolean("writable") != false,
            )
        }
    }
}

/** Secret content is omitted from diagnostics. Instances exist only until the mutation finishes. */
class SettingsOp(val path: List<String>, val value: JsonElement?, val unset: Boolean = false) {
    fun toJson() = buildJsonObject {
        put("op", if (unset) "unset" else "set")
        put("path", JsonArray(path.map(::JsonPrimitive)))
        if (!unset) put("value", value ?: JsonNull)
    }

    override fun toString() = "SettingsOp(path=$path, unset=$unset)"
}

fun lookupSetting(root: JsonElement?, path: List<String>): JsonElement? {
    var value = root
    for (part in path) value =
        when (val parent = value) {
            is JsonObject -> parent[part]
            is JsonArray -> part.toIntOrNull()?.let(parent::getOrNull)
            else -> null
        }
    return value
}

fun replaceSetting(
    root: JsonElement?,
    path: List<String>,
    value: JsonElement?,
    unset: Boolean = false,
): JsonElement {
    if (path.isEmpty()) return if (unset) JsonObject(emptyMap()) else value ?: JsonNull
    val key = path.first()
    val tail = path.drop(1)
    if (root is JsonArray) {
        val index = key.toIntOrNull() ?: return root
        if (index !in root.indices) return root
        val copy = root.toMutableList()
        if (tail.isEmpty() && unset) copy.removeAt(index)
        else copy[index] = replaceSetting(copy[index], tail, value, unset)
        return JsonArray(copy)
    }
    val copy = (root as? JsonObject).orEmpty().toMutableMap()
    if (tail.isEmpty() && unset) copy.remove(key)
    else copy[key] = replaceSetting(copy[key], tail, value, unset)
    return JsonObject(copy)
}

suspend fun ProtocolClient.describeSettings(): SettingsDescription =
    withContext(Dispatchers.Default) {
        val raw = connection.call("settings/describe").objectValue()
        val writable = raw.boolean("writable") != false
        SettingsDescription(
            (raw["namespaces"] as? JsonArray
                    ?: throw HostProtocolException("Missing settings namespaces"))
                .map { SettingsNamespaceView.parse(it, writable) }
                .toPersistentList(),
            writable,
            raw.boolean("hasDocument") == true,
        )
    }

suspend fun ProtocolClient.mutateSettingsView(
    previous: SettingsNamespaceView,
    ops: List<SettingsOp>,
): SettingsNamespaceView =
    withContext(Dispatchers.Default) {
        require(previous.writable)
        SettingsNamespaceView.parse(
            connection.call(
                "settings/mutate",
                buildJsonObject {
                    put("ns", previous.ns)
                    put("expectedRevision", previous.revision)
                    put("ops", JsonArray(ops.map(SettingsOp::toJson)))
                },
            ),
            previous.writable,
        )
    }

class PluginConfigEntryDetail(
    val entryId: String,
    val revision: String,
    val schema: SchemaNode,
    val value: JsonObject,
    val base: JsonObject?,
    val user: JsonObject?,
    val secrets: PersistentList<SettingsSecretSlot>,
    val writable: Boolean,
)

val ProtocolClient.canReadCompletePluginConfig
    get() = connection is HostConnection

suspend fun ProtocolClient.pluginConfigExchange(
    entryId: String?,
    body: JsonObject? = null,
): JsonElement {
    val http =
        connection as? HostConnection ?: throw HostRemoteException("plugin-config/unsupported")
    val request =
        http.request("/compose/extensions/v1/plugin-config").apply {
            if (entryId != null) url.appendPathSegments(entryId)
            timeout {
                requestTimeoutMillis = if (body == null) 10000 else 60000
                socketTimeoutMillis = if (body == null) 10000 else 60000
            }
            if (body != null) {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
    return http.exchange(request) { response ->
        if (response.status.value != 200) {
            val body = response.boundedBytes(64L * 1024).toString(Charsets.UTF_8)
            val raw = runCatching { wireJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = raw?.get("error") as? JsonObject ?: raw
            throw HostRemoteException(
                "plugin-config/${error?.string("code") ?: "http-${response.status.value}"}",
                error?.get("path"),
                error?.string("message"),
            )
        }
        response.jsonObject(4L * 1024 * 1024)
    }
}

private fun pluginConfigDetail(value: JsonElement): PluginConfigEntryDetail {
    val wrapper = value.objectValue()
    val raw = wrapper["entry"] as? JsonObject ?: wrapper
    val slots =
        (raw["secrets"] as? JsonArray)
            .orEmpty()
            .mapNotNull { item ->
                val slot = item as? JsonObject ?: return@mapNotNull null
                val ref =
                    slot.string("credentialRef") ?: (slot["binding"] as? JsonObject)?.string("ref")
                SettingsSecretSlot(
                    (slot["path"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                    slot.boolean("configured") == true,
                    ref,
                    slot.boolean("writable") == true && ref != null,
                )
            }
            .toPersistentList()
    return PluginConfigEntryDetail(
        raw.requiredString("entryId"),
        raw.requiredString("revision"),
        SchemaNode.parse(raw["schema"]?.toToolPayload()),
        raw["value"] as? JsonObject ?: JsonObject(emptyMap()),
        raw["base"] as? JsonObject,
        raw["user"] as? JsonObject,
        slots,
        raw.boolean("writable") == true,
    )
}

suspend fun ProtocolClient.readPluginConfig(entryId: String): PluginConfigEntryDetail =
    withContext(Dispatchers.Default) { pluginConfigDetail(pluginConfigExchange(entryId)) }

suspend fun ProtocolClient.writePluginConfig(
    previous: PluginConfigEntryDetail,
    ops: List<SettingsOp>,
): PluginConfigEntryDetail =
    withContext(Dispatchers.Default) {
        require(previous.writable)
        pluginConfigDetail(
            pluginConfigExchange(
                previous.entryId,
                buildJsonObject {
                    put("revision", previous.revision)
                    put("ops", JsonArray(ops.map(SettingsOp::toJson)))
                },
            ),
        )
    }

suspend fun ProtocolClient.writeNamespaceSecret(
    previous: SettingsNamespaceView,
    path: List<String>,
    key: ProviderKey?,
): SettingsNamespaceView {
    require(schemaAt(previous.schema, path)?.secret == true)
    return mutateSettingsView(
        previous,
        listOf(SettingsOp(path, key?.let { JsonPrimitive(it.text()) }, unset = key == null)),
    )
}

fun parseLocalizedText(value: JsonElement?): LocalizedText? =
    dev.fughetta.dshcompose.domain.text.parseLocalizedText(value?.toToolPayload())

class SettingsDescription(
    val namespaces: PersistentList<SettingsNamespaceView>,
    val writable: Boolean,
    val hasDocument: Boolean,
)

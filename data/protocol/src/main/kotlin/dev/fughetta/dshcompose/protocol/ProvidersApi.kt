package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

private fun providerView(value: JsonObject, writable: Boolean): ProviderSettings {
    fun routes(layer: String) =
        ((value[layer] as? JsonObject)?.get("providers") as? JsonObject)
            .orEmpty()
            .keys
            .toPersistentSet()
    val providers = ((value["value"] as? JsonObject)?.get("providers") as? JsonObject).orEmpty()
    return ProviderSettings(
        providers
            .map { (route, raw) ->
                val profile = raw.objectValue()
                ProviderProfile(
                    route,
                    profile.string("displayName"),
                    ProviderApi.entries.firstOrNull { it.wire == profile.string("api") },
                    profile.string("baseURL"),
                    profile.string("apiKeyEnv")?.takeIf { it.isNotBlank() },
                    (profile["models"] as? JsonArray)
                        .orEmpty()
                        .map(ProviderModelSpec::fromJson)
                        .toPersistentList(),
                    (profile["defaultInput"] as? JsonArray)
                        ?.map { it.jsonPrimitive.content }
                        ?.takeIf { it.isNotEmpty() } ?: listOf("text"),
                    profile.long("defaultContextWindow"),
                    profile.long("defaultMaxTokens"),
                    (profile["modelOverrides"] as? JsonObject)
                        .orEmpty()
                        .mapValues { ProviderModelOverride.fromJson(it.value) }
                        .toPersistentMap(),
                )
            }
            .toPersistentList(),
        value.requiredLong("revision"),
        writable,
        routes("user"),
        routes("base"),
    )
}

suspend fun ProtocolClient.providerSettings(): ProviderSettings? =
    withContext(Dispatchers.Default) {
        val description = connection.call("settings/describe").objectValue()
        val section =
            (description["namespaces"] as? JsonArray)
                .orEmpty()
                .map { it.objectValue() }
                .firstOrNull { it.string("ns") == "llm-pi-ai" }
        section?.let { providerView(it, description.boolean("writable") != false) }
    }

private fun settingOp(path: List<String>, value: JsonElement?): JsonObject = buildJsonObject {
    put("op", if (value == null) "unset" else "set")
    put("path", JsonArray(path.map(::JsonPrimitive)))
    value?.let { put("value", it) }
}

private suspend fun ProtocolClient.changeProviders(
    ops: List<JsonObject>,
    revision: Long,
): ProviderSettings =
    withContext(Dispatchers.Default) {
        require(ops.isNotEmpty()) { "No provider edits" }
        val response =
            connection
                .call(
                    "settings/mutate",
                    buildJsonObject {
                        put("ns", "llm-pi-ai")
                        put("expectedRevision", revision)
                        put("ops", JsonArray(ops))
                    },
                )
                .objectValue()
        providerView(response, true)
    }

suspend fun ProtocolClient.createProvider(
    route: String,
    displayName: String,
    api: ProviderApi,
    baseUrl: String,
    credentialRef: String?,
    models: List<ProviderModelSpec>,
    expectedRevision: Long,
): ProviderSettings {
    require(validProviderRoute(route))
    require(models.isNotEmpty())
    credentialRef?.let(::checkCredentialRef)
    val value = buildJsonObject {
        displayName.takeIf { it.isNotBlank() }?.let { put("displayName", it) }
        put("api", api.wire)
        put("baseURL", baseUrl)
        credentialRef?.let { put("apiKeyEnv", it) }
        put("models", JsonArray(models.map { it.toJson() }))
    }
    return changeProviders(listOf(settingOp(listOf("providers", route), value)), expectedRevision)
}

suspend fun ProtocolClient.updateProvider(
    previous: ProviderProfile,
    displayName: String,
    credentialRef: String?,
    api: ProviderApi? = null,
    baseUrl: String? = null,
    models: List<ProviderModelSpec>? = null,
    expectedRevision: Long,
    modelOverrides: Map<String, ProviderModelOverride>? = null,
): ProviderSettings {
    require(validProviderRoute(previous.route))
    credentialRef?.let(::checkCredentialRef)
    val ops = buildList {
        fun edit(field: String, value: JsonElement?) {
            add(settingOp(listOf("providers", previous.route, field), value))
        }
        val name = displayName.takeIf { it.isNotBlank() }
        if (name != previous.displayName) edit("displayName", name?.let(::JsonPrimitive))
        if (credentialRef != previous.credentialRef)
            edit("apiKeyEnv", credentialRef?.let(::JsonPrimitive))
        if (api != null && api != previous.api) edit("api", JsonPrimitive(api.wire))
        if (baseUrl != null && baseUrl != previous.baseUrl) edit("baseURL", JsonPrimitive(baseUrl))
        if (models != null && models.map { it.toJson() } != previous.models.map { it.toJson() })
            edit("models", JsonArray(models.map { it.toJson() }))
        if (modelOverrides != null) {
            require(modelOverrides.keys.all { it.isNotBlank() })
            if (modelOverrides.isEmpty() && previous.modelOverrides.isNotEmpty())
                edit("modelOverrides", null)
            else
                for (id in previous.modelOverrides.keys + modelOverrides.keys) {
                    val next = modelOverrides[id]?.toJson()
                    if (next != previous.modelOverrides[id]?.toJson())
                        add(
                            settingOp(
                                listOf("providers", previous.route, "modelOverrides", id),
                                next,
                            ),
                        )
                }
        }
    }
    return changeProviders(ops, expectedRevision)
}

suspend fun ProtocolClient.removeProvider(route: String, expectedRevision: Long): ProviderSettings {
    require(validProviderRoute(route))
    return changeProviders(listOf(settingOp(listOf("providers", route), null)), expectedRevision)
}

/** The configured route keeps its credential on the Host; it is never fetched into the App. */
suspend fun ProtocolClient.discoverModels(provider: String): PersistentList<DiscoveredModel> {
    require(provider.isNotBlank())
    return discoverModelRequest(buildJsonObject { put("provider", provider) })
}

/** Draft credentials are used for this discovery request only; this API never stores them. */
suspend fun ProtocolClient.discoverModels(
    baseUrl: String,
    api: ProviderApi,
    key: ProviderKey? = null,
): PersistentList<DiscoveredModel> =
    discoverModelRequest(
        buildJsonObject {
            put("baseURL", baseUrl)
            put("api", api.wire)
            key?.let { put("apiKey", it.text()) }
        },
    )

private suspend fun ProtocolClient.discoverModelRequest(
    request: JsonObject,
): PersistentList<DiscoveredModel> =
    withTimeoutOrNull(30_000) {
        withContext(Dispatchers.Default) {
            val rows =
                connection.call(
                    "llm/discoverModels",
                    buildJsonObject {
                        put("settingsNs", "llm-pi-ai")
                        put("request", request)
                    },
                ) as? JsonArray ?: throw HostProtocolException("Invalid discovered models")
            rows
                .map { raw ->
                    val model = raw.objectValue()
                    DiscoveredModel(
                        model.requiredString("id"),
                        model.string("name"),
                        model.long("contextWindow"),
                        model.long("maxTokens"),
                        (model["inputModalities"] as? JsonArray)
                            ?.map { it.jsonPrimitive.content }
                            ?.toPersistentList(),
                    )
                }
                .distinctBy { it.id }
                .toPersistentList()
        }
    } ?: throw HostTransportException()

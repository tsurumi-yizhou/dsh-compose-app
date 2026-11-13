package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private data class DefaultField(val type: String, val disabled: Boolean)

private fun defaultField(schema: JsonObject, field: String): DefaultField? {
    val refs = schema["refs"] as? JsonObject
    fun reference(value: JsonElement?): JsonObject? =
        if (refs == null) value as? JsonObject
        else value?.let { refs[it.jsonPrimitive.content] as? JsonObject }
    val root = if (refs == null) schema else reference(schema["uid"]) ?: return null
    fun effective(node: JsonObject, seen: Set<JsonObject> = emptySet()): JsonObject? {
        if (node in seen) return null
        return if (node.string("type") in setOf("transform", "lazy"))
            reference(node["inner"])?.let { effective(it, seen + node) }
        else node
    }
    fun walk(
        node: JsonObject,
        remaining: String?,
        disabled: Boolean,
        seen: Set<JsonObject>,
    ): DefaultField? {
        if (node in seen) return null
        val visited = seen + node
        val locked = disabled || (node["meta"] as? JsonObject)?.boolean("disabled") == true
        return when (val type = node.string("type")) {
            "transform",
            "lazy" -> reference(node["inner"])?.let { walk(it, remaining, locked, visited) }
            "union" -> {
                val children = (node["list"] as? JsonArray).orEmpty().mapNotNull(::reference)
                val members = children.filterNot { child ->
                    effective(child)?.let {
                        it.string("type") == "const" &&
                            (it["value"] == null || it["value"] == JsonNull)
                    } == true
                }
                members.singleOrNull()?.let { walk(it, remaining, locked, visited) }
            }
            "intersect" ->
                (node["list"] as? JsonArray).orEmpty().asReversed().firstNotNullOfOrNull { raw ->
                    reference(raw)?.let { walk(it, remaining, locked, visited) }
                }
            "object" ->
                if (remaining != null)
                    reference((node["dict"] as? JsonObject)?.get(remaining))?.let {
                        walk(it, null, locked, visited)
                    }
                else DefaultField(type, locked)
            null -> null
            else -> if (remaining == null) DefaultField(type, locked) else null
        }
    }
    return walk(root, field, false, emptySet())
}

private fun modelDefaults(section: JsonObject, writable: Boolean): ModelDefaults {
    val value = (section["value"] as? JsonObject).orEmpty()
    val provider = (value["provider"] as? JsonPrimitive)?.contentOrNull
    val model = (value["model"] as? JsonPrimitive)?.contentOrNull
    val effort = (value["reasoningEffort"] as? JsonPrimitive)?.contentOrNull
    val schema = section["schema"] as? JsonObject ?: JsonObject(emptyMap())
    val fields = listOf("provider", "model", "reasoningEffort", "rememberLastSelection")
    val editable = fields.all { key ->
        defaultField(schema, key)?.let {
            !it.disabled && it.type == if (key == "rememberLastSelection") "boolean" else "string"
        } == true
    }
    return ModelDefaults(
        section.requiredLong("revision"),
        if (!provider.isNullOrBlank() && !model.isNullOrBlank())
            ModelSelection(provider, model, effort)
        else null,
        (value["rememberLastSelection"] as? JsonPrimitive)?.booleanOrNull != false,
        (section["base"] as? JsonObject)?.string("reasoningEffort"),
        writable && editable,
    )
}

suspend fun ProtocolClient.modelDefaults(): ModelDefaults? =
    withContext(Dispatchers.Default) {
        val response = connection.call("settings/describe").objectValue()
        (response["namespaces"] as? JsonArray)
            .orEmpty()
            .map { it.objectValue() }
            .firstOrNull { it.string("ns") == "agent-default-model" }
            ?.let { modelDefaults(it, response.boolean("writable") != false) }
    }

/** Changes only new-chat defaults. Never invokes session/selectModel. */
suspend fun ProtocolClient.setModelDefaults(
    previous: ModelDefaults,
    selection: ModelSelection,
    rememberLastSelection: Boolean,
) {
    connection.call(
        "settings/mutate",
        buildJsonObject {
            put("ns", "agent-default-model")
            put("expectedRevision", previous.revision)
            putJsonArray("ops") {
                fun edit(key: String, value: JsonElement?) {
                    add(
                        buildJsonObject {
                            put("op", if (value == null) "unset" else "set")
                            putJsonArray("path") { add(key) }
                            value?.let { put("value", it) }
                        },
                    )
                }
                edit("provider", JsonPrimitive(selection.provider))
                edit("model", JsonPrimitive(selection.model))
                edit("reasoningEffort", selection.reasoningEffort?.let(::JsonPrimitive))
                edit("rememberLastSelection", JsonPrimitive(rememberLastSelection))
            }
        },
    )
}

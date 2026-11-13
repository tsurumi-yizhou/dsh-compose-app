package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

internal val ProviderApi.wire: String
    get() =
        when (this) {
            ProviderApi.OpenAiCompletions -> "openai-completions"
            ProviderApi.OpenAiResponses -> "openai-responses"
            ProviderApi.AnthropicMessages -> "anthropic-messages"
        }

private val ModelSpecFields =
    setOf("id", "name", "contextWindow", "maxTokens", "input", "reasoningEfforts")

private fun reasoning(value: JsonElement?): ProviderReasoningEfforts? =
    when {
        value is JsonObject ->
            ProviderReasoningEfforts(
                value
                    .mapValues { (level, raw) ->
                        when (raw) {
                            JsonNull -> null
                            JsonPrimitive(level) -> ProviderEffortValue(level = level)
                            else -> ProviderEffortValue(payload = raw)
                        }
                    }
                    .toPersistentMap(),
            )
        value == JsonPrimitive(false) -> ProviderReasoningEfforts(persistentMapOf())
        else -> null
    }

internal fun ProviderModelSpec.Companion.fromJson(value: JsonElement): ProviderModelSpec {
    val raw = value.objectValue()
    val additional = raw.filterKeys { it !in ModelSpecFields }
    return ProviderModelSpec(
        raw.requiredString("id"),
        raw.string("name"),
        raw.long("contextWindow"),
        raw.long("maxTokens"),
        (raw["input"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toPersistentList(),
        reasoning(raw["reasoningEfforts"]),
        ProviderModelOrigin(additional.isNotEmpty(), raw, additional),
    )
}

internal fun ProviderModelSpec.toJson(): JsonObject {
    val raw = origin?.payload as? JsonObject ?: JsonObject(emptyMap())
    val next = raw.toMutableMap()
    fun assign(key: String, field: JsonElement?) {
        if (field == null) next.remove(key) else next[key] = field
    }
    next["id"] = JsonPrimitive(id)
    if (origin == null || name != raw.string("name")) assign("name", name?.let(::JsonPrimitive))
    if (origin == null || contextWindow != raw.long("contextWindow"))
        assign("contextWindow", contextWindow?.let(::JsonPrimitive))
    if (origin == null || maxTokens != raw.long("maxTokens"))
        assign("maxTokens", maxTokens?.let(::JsonPrimitive))
    val oldInput = (raw["input"] as? JsonArray)?.map { it.jsonPrimitive.content }
    if (origin == null || input != oldInput)
        assign("input", input?.let { JsonArray(it.map(::JsonPrimitive)) })
    if (reasoning != reasoning(raw["reasoningEfforts"])) {
        assign(
            "reasoningEfforts",
            reasoning?.let { requested ->
                if (requested.levels.none { it != "off" }) JsonPrimitive(false)
                else
                    buildJsonObject {
                        for ((level, value) in requested.values) {
                            put(
                                level,
                                when {
                                    value == null -> JsonNull
                                    else ->
                                        (value.payload as? JsonElement)
                                            ?: JsonPrimitive(value.level.orEmpty())
                                },
                            )
                        }
                    }
            },
        )
    }
    return JsonObject(next)
}

internal fun ProviderModelOverride.Companion.fromJson(value: JsonElement): ProviderModelOverride {
    val raw = value.objectValue()
    return ProviderModelOverride(
        raw.long("contextWindow"),
        raw.takeIf { it.isNotEmpty() }
            ?.let {
                ProviderModelOverrideOrigin(it.keys.any { key -> key != "contextWindow" }, it)
            },
    )
}

internal fun ProviderModelOverride.toJson(): JsonObject {
    val raw = origin?.payload as? JsonObject ?: JsonObject(emptyMap())
    val next = raw.toMutableMap()
    if (contextWindow != raw.long("contextWindow")) {
        if (contextWindow == null) next.remove("contextWindow")
        else next["contextWindow"] = JsonPrimitive(contextWindow)
    }
    return JsonObject(next)
}

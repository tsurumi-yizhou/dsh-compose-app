package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.usage.SessionUsage
import kotlinx.serialization.json.*

/** Per-key sequence merging prevents a delayed HTTP seed from replacing newer control values. */
internal fun sessionUsage(values: JsonObject): SessionUsage {
    fun obj(key: String) = (values[key] as? JsonObject) ?: JsonObject(emptyMap())
    val tokens = obj("tokenUsage")
    val pressure = obj("contextPressure")
    val split = obj("contextBreakdown")
    val stats = obj("sessionStats")
    return SessionUsage(
        tokens.long("uncachedInputTokens") ?: 0,
        tokens.long("outputTokens") ?: 0,
        tokens.long("cacheReadTokens") ?: 0,
        tokens.long("cacheWriteTokens") ?: 0,
        pressure.long("pressureTokens"),
        pressure.long("projectedTokens"),
        pressure.long("contextWindow"),
        split.long("systemTokens"),
        split.long("toolsTokens"),
        split.long("messageTokens"),
        stats.long("turns"),
        stats.long("llmMs"),
        stats.long("toolMs"),
    )
}

class SessionUsageFold {
    private val keys = setOf("tokenUsage", "contextPressure", "contextBreakdown", "sessionStats")
    private val seq = mutableMapOf<String, Long>()
    private var values = JsonObject(emptyMap())
    val usage
        get() = sessionUsage(values)

    fun clear() {
        seq.clear()
        values = JsonObject(emptyMap())
    }

    fun seed(projections: JsonObject, replace: Boolean = false) {
        if (replace) {
            seq.clear()
            values = JsonObject(emptyMap())
        }
        val after = projections.long("asOfSeq") ?: -1
        val source = projections["values"] as? JsonObject ?: JsonObject(emptyMap())
        keys.forEach { key ->
            if (after > (seq[key] ?: Long.MIN_VALUE)) {
                seq[key] = after
                values =
                    JsonObject(
                        values.toMutableMap().apply {
                            if (key in source) put(key, source.getValue(key)) else remove(key)
                        },
                    )
            }
        }
    }
}

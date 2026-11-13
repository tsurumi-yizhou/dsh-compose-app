package dev.fughetta.dshcompose.domain.usage

import dev.fughetta.dshcompose.domain.chat.ToolPayload

/**
 * Per-key sequence merge of the host's context projections, so a delayed HTTP seed never replaces
 * newer values that arrived on the followed session stream.
 */
class UsageFold {
    private val keys = setOf("tokenUsage", "contextPressure", "contextBreakdown", "sessionStats")
    private val sequence = mutableMapOf<String, Long>()
    private var values: ToolPayload = ToolPayload.Null

    fun clear() {
        sequence.clear()
        values = ToolPayload.Null
    }

    fun seed(projections: ToolPayload, replace: Boolean = false) {
        if (replace) clear()
        val after = projections["asOfSeq"]?.asLong ?: -1
        val source = projections["values"]?.asObject ?: return
        val current = values.asObject?.toMutableMap() ?: linkedMapOf()
        for (key in keys) {
            if (after > (sequence[key] ?: Long.MIN_VALUE)) {
                sequence[key] = after
                val value = source[key]
                if (value != null && value != ToolPayload.Null) current[key] = value
                else current.remove(key)
            }
        }
        values = ToolPayload.Obj(current)
    }

    fun usage(): SessionUsage = sessionUsageFrom(values.asObject.orEmpty())
}

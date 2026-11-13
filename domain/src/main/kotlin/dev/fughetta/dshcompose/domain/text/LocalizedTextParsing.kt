package dev.fughetta.dshcompose.domain.text

import dev.fughetta.dshcompose.domain.chat.ToolPayload

fun parseLocalizedText(value: ToolPayload?): LocalizedText? {
    val entries =
        when (value) {
            is ToolPayload.Text ->
                if (value.value.isNotBlank()) mapOf("" to value.value) else emptyMap()
            is ToolPayload.Obj ->
                value.fields
                    .mapNotNull { (key, item) ->
                        (item as? ToolPayload.Text)
                            ?.takeIf { it.value.isNotBlank() }
                            ?.let { key.lowercase().replace('_', '-') to it.value }
                    }
                    .toMap()
            else -> emptyMap()
        }
    return entries.takeIf { it.isNotEmpty() }?.let(::LocalizedText)
}

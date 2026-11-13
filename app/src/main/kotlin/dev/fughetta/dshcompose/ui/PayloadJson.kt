package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/** Converts form input and renderer values to and from the domain tree. */
fun JsonElement.toUiPayload(): ToolPayload =
    when (this) {
        is JsonNull -> ToolPayload.Null
        is JsonPrimitive ->
            when {
                isString -> ToolPayload.Text(content)
                booleanOrNull != null -> ToolPayload.Bool(boolean)
                else -> ToolPayload.Num(content)
            }
        is JsonArray -> ToolPayload.Arr(map { it.toUiPayload() })
        is JsonObject ->
            ToolPayload.Obj(entries.associate { (key, value) -> key to value.toUiPayload() })
    }

@OptIn(ExperimentalSerializationApi::class)
fun ToolPayload.toUiJson(): JsonElement =
    when (this) {
        is ToolPayload.Null -> JsonNull
        is ToolPayload.Bool -> JsonPrimitive(value)
        is ToolPayload.Num -> JsonUnquotedLiteral(raw)
        is ToolPayload.Text -> JsonPrimitive(value)
        is ToolPayload.Arr -> JsonArray(items.map { it.toUiJson() })
        is ToolPayload.Obj ->
            JsonObject(fields.entries.associate { (key, value) -> key to value.toUiJson() })
    }

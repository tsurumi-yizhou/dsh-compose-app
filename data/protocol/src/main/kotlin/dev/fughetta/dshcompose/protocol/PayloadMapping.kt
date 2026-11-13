package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/**
 * The wire boundary: event JSON becomes a domain [ToolPayload] when a tool call is recorded, and
 * the few data-layer readers that still walk JSON convert back through [toJsonElement].
 */
fun JsonElement.toToolPayload(): ToolPayload =
    when (this) {
        is JsonNull -> ToolPayload.Null
        is JsonPrimitive ->
            when {
                isString -> ToolPayload.Text(content)
                booleanOrNull != null -> ToolPayload.Bool(boolean)
                else -> ToolPayload.Num(content)
            }
        is JsonArray -> ToolPayload.Arr(map { it.toToolPayload() })
        is JsonObject ->
            ToolPayload.Obj(entries.associate { (key, value) -> key to value.toToolPayload() })
    }

@OptIn(ExperimentalSerializationApi::class)
fun ToolPayload.toJsonElement(): JsonElement =
    when (this) {
        is ToolPayload.Null -> JsonNull
        is ToolPayload.Bool -> JsonPrimitive(value)
        is ToolPayload.Num -> JsonUnquotedLiteral(raw)
        is ToolPayload.Text -> JsonPrimitive(value)
        is ToolPayload.Arr -> JsonArray(items.map { it.toJsonElement() })
        is ToolPayload.Obj ->
            JsonObject(fields.entries.associate { (key, value) -> key to value.toJsonElement() })
    }

package dev.fughetta.dshcompose.phone

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/**
 * JSON for the phone bridge (MCP framing, guest config, policies): a plain Kotlin value tree on top
 * of kotlinx.serialization. Objects parse to [LinkedHashMap] (key order kept), arrays to [List],
 * integers that fit to [Long], other numbers to [Double].
 */
object Json {
    class JsonException(message: String) : Exception(message)

    private val format = Json { isLenient = false }

    fun parse(text: String): Any? =
        try {
            // kotlinx reads a bare token ("undefined") as a string; JSON does not. Only these
            // first characters can start a JSON value.
            val first = text.trimStart().firstOrNull()
            if (first == null || first !in "\"[{0123456789-tfn") throw JsonException("invalid json")
            value(format.parseToJsonElement(text))
        } catch (error: SerializationException) {
            throw JsonException(error.message ?: "invalid json")
        } catch (error: IllegalArgumentException) {
            throw JsonException(error.message ?: "invalid json")
        }

    fun write(value: Any?): String = element(value).toString()

    private fun value(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null
            is JsonPrimitive ->
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.boolean
                    element.longOrNull != null -> element.long
                    else -> element.doubleOrNull ?: element.content
                }
            is JsonArray -> element.map(::value)
            is JsonObject ->
                LinkedHashMap<String, Any?>(element.size).apply {
                    element.forEach { (key, child) -> put(key, value(child)) }
                }
        }

    private fun element(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Int,
            is Long,
            is Short,
            is Byte -> JsonPrimitive((value as Number).toLong())
            is Float,
            is Double -> JsonPrimitive((value as Number).toDouble())
            is Number -> JsonPrimitive(value.toDouble())
            is String -> JsonPrimitive(value)
            is Map<*, *> ->
                buildJsonObject { for ((key, child) in value) put(key.toString(), element(child)) }
            is Iterable<*> -> JsonArray(value.map(::element))
            is Array<*> -> JsonArray(value.map(::element))
            else -> JsonPrimitive(value.toString())
        }
}

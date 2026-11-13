package dev.fughetta.dshcompose.diagnostics

import kotlinx.serialization.json.*

/** Converts already-sanitized diagnostic values to kotlinx.serialization JSON. */
fun Any?.asJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> ->
            buildJsonObject {
                for ((key, value) in this@asJsonElement) put(key.toString(), value.asJsonElement())
            }
        is Iterable<*> -> JsonArray(map { it.asJsonElement() })
        is Array<*> -> JsonArray(map { it.asJsonElement() })
        else -> JsonPrimitive(toString())
    }

/** The plain Kotlin value behind a JSON primitive, list or object (diagnostic readers only). */
fun JsonElement.plainValue(): Any? =
    when (this) {
        is JsonNull -> null
        is JsonPrimitive ->
            when {
                isString -> content
                booleanOrNull != null -> boolean
                longOrNull != null -> long
                doubleOrNull != null -> double
                else -> content
            }
        is JsonArray -> map { it.plainValue() }
        is JsonObject -> entries.associate { (key, value) -> key to value.plainValue() }
    }

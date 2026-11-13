package dev.fughetta.dshcompose.engine.runtime

import kotlinx.serialization.json.*

/** One pretty-printing JSON dialect for the runtime's package manifests and state files. */
internal val runtimeJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Returns a copy of this object with [key] replaced, keeping every other entry in place. */
internal fun JsonObject.with(key: String, value: JsonElement): JsonObject =
    JsonObject(LinkedHashMap(this).apply { put(key, value) })

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** All elements as strings; null when the value is missing or holds a non-string element. */
internal fun JsonObject.strings(key: String): List<String>? =
    (this[key] as? JsonArray)?.let { array -> array.strings() }

internal fun JsonArray.strings(): List<String>? {
    val values = ArrayList<String>(size)
    for (element in this) {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        values += primitive.content
    }
    return values
}

internal fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

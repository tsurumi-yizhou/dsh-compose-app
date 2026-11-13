package dev.fughetta.dshcompose.protocol

import kotlinx.serialization.json.*

fun JsonObject.npString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.npBool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

fun JsonObject.npStrings(key: String): List<String> =
    (get(key) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .orEmpty()

fun JsonObject.npObjects(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

package dev.fughetta.dshcompose.data.media

import dev.fughetta.dshcompose.domain.media.MediaParameters
import dev.fughetta.dshcompose.protocol.json
import dev.fughetta.dshcompose.protocol.parseMediaParameters
import kotlinx.serialization.json.*

fun decodeMediaModes(encoded: String?): Map<String, MediaParameters> {
    val root = encoded?.let { Json.parseToJsonElement(it) as? JsonObject }
    return root
        .orEmpty()
        .mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { key to parseMediaParameters(it) }
        }
        .toMap()
}

fun encodeMediaModes(modes: Map<String, MediaParameters>): String =
    JsonObject(modes.mapValues { it.value.json() }).toString()

fun decodeMediaParameters(encoded: String): MediaParameters =
    parseMediaParameters(Json.parseToJsonElement(encoded).jsonObject)

fun encodeMediaParameters(parameters: MediaParameters): String = parameters.json().toString()

package dev.fughetta.dshcompose.notify

import kotlinx.serialization.json.*

/** Small reads over host event JSON at the notification boundary. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

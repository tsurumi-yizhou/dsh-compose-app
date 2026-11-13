package dev.fughetta.dshcompose.domain.providers

import java.net.IDN
import java.net.URI

fun validProviderBaseUrl(raw: String): Boolean = runCatching {
    val value = raw.trim()
    val uri = URI(value)
    val authority = uri.rawAuthority.orEmpty()
    val host = uri.host ?: URI("http://${IDN.toASCII(authority)}").host
    uri.scheme?.lowercase() in setOf("http", "https") &&
        !host.isNullOrBlank() &&
        '@' !in authority &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        value.none(Char::isWhitespace)
}
    .getOrDefault(false)

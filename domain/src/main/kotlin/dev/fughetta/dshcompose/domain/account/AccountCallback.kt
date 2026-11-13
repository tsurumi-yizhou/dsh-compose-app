package dev.fughetta.dshcompose.domain.account

import java.net.URI

fun accountCallbackOrigin(baseUrl: String?): String? {
    val uri =
        try {
            baseUrl?.let(::URI)
        } catch (_: Exception) {
            null
        } ?: return null
    if (
        uri.scheme != "http" ||
            uri.port !in 1..65535 ||
            uri.host !in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    )
        return null
    val host = if (uri.host.contains(':')) "[::1]" else uri.host
    return "http://$host:${uri.port}"
}

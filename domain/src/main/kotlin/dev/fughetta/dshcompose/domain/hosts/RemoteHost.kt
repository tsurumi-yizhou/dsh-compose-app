package dev.fughetta.dshcompose.domain.hosts

import java.net.URI

/** One independently deployed compose service. Model provider secrets stay on that service. */
data class RemoteHost(val id: String, val name: String, val url: String)

class HostPairing(val url: String, val code: String) {
    override fun toString() = "HostPairing(url=$url)"
}

fun hostHttpHost(host: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
    val octets = name.split('.').map { it.toIntOrNull() }
    val tailnetIp =
        octets.size == 4 &&
            octets.all { it != null && it in 0..255 } &&
            octets[0] == 100 &&
            octets[1]!! in 64..127
    return name in setOf("127.0.0.1", "localhost", "::1") ||
        tailnetIp ||
        name.startsWith("fd7a:115c:a1e0:") ||
        name.endsWith(".ts.net") ||
        name.matches(Regex("[a-zA-Z][a-zA-Z0-9-]*"))
}

fun hostServiceUrl(text: String): String? =
    try {
        val input = text.trim()
        val explicit = input.contains("://")
        val parsed = URI(if (explicit) input else "http://$input")
        val uri =
            if (!explicit && parsed.port == -1)
                URI(
                    parsed.scheme,
                    parsed.rawUserInfo,
                    parsed.host,
                    3095,
                    parsed.path,
                    parsed.rawQuery,
                    parsed.rawFragment,
                )
            else parsed
        if (
            uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.port !in -1..65535 ||
                uri.port == 0 ||
                (uri.scheme != "https" && !(uri.scheme == "http" && hostHttpHost(uri.host))) ||
                uri.path.orEmpty().split('/').any { it == "." || it == ".." }
        )
            null
        else uri.toASCIIString().trimEnd('/')
    } catch (_: Exception) {
        null
    }

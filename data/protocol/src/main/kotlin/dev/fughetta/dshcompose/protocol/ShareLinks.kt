package dev.fughetta.dshcompose.protocol

import io.ktor.http.*
import java.net.URI

class ShareCodeLink(
    val code: String? = null,
    val gist: String? = null,
    val sum: String? = null,
    val kind: ShareKind? = null,
) {
    val appUrl
        get() =
            if (code != null) "dsh://share/${kind?.name ?: "plugin"}?p=$code"
            else "dsh://share/profile?gist=$gist&c=$sum"

    override fun toString() = "ShareCodeLink"

    companion object {
        fun parse(text: String): ShareCodeLink? = runCatching {
            val uri = URI(text.trim())
            if (uri.userInfo != null || uri.port != -1) return null
            if (uri.scheme != "dsh" || uri.host != "share") return null
            val params = parseQueryString(uri.rawQuery.orEmpty())
            val kind = ShareKind.entries.find { it.name == uri.path.trim('/') } ?: return null
            params["p"]?.let { code ->
                if (code.length <= 6 * 1024 * 1024 && '.' in code)
                    return ShareCodeLink(code = code, kind = kind)
            }
            val gist = params["gist"] ?: return null
            val sum = params["c"] ?: return null
            if (gist.matches(Regex("[0-9a-f]{20,40}")) && sum.matches(Regex("[A-Za-z0-9_-]{8}")))
                ShareCodeLink(gist = gist, sum = sum, kind = ShareKind.profile)
            else null
        }
            .getOrNull()

        fun find(text: String): ShareCodeLink? =
            Regex("dsh://share/[^\\s\"'<>]+")
                .findAll(text)
                .mapNotNull { parse(it.value) }
                .firstOrNull()
    }
}

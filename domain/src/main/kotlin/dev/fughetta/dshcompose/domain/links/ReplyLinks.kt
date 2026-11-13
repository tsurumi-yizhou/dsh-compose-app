package dev.fughetta.dshcompose.domain.links

import java.net.URI
import java.net.URISyntaxException

sealed interface ReplyLinkTarget {
    data class External(val address: String) : ReplyLinkTarget

    data class Workspace(val path: String, val website: String? = null) : ReplyLinkTarget

    /** `dsh://open/<id>`; which ids name a page is the app's call. */
    data class Page(val id: String) : ReplyLinkTarget

    data object Unsupported : ReplyLinkTarget
}

private val drivePath = Regex("""^[A-Za-z]:[\\/]""")
private val pathScheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

private fun destination(value: String) = value.trim().removeSurrounding("<", ">").trim()

/** Decode exactly once. Paths remain Host paths, never Android file URLs. */
fun workspaceLinkPath(address: String, baseDirectory: String? = null): String? {
    val target = destination(address)
    if (
        target.isEmpty() ||
            target.startsWith('#') ||
            target.startsWith('?') ||
            target.startsWith("//") ||
            target.any { it.isISOControl() }
    )
        return null
    val windows = drivePath.containsMatchIn(target)
    val raw =
        if (windows) target.substringBefore('#').substringBefore('?')
        else {
            val uri =
                try {
                    URI(target.replace(" ", "%20"))
                } catch (_: URISyntaxException) {
                    return null
                }
            if (
                uri.scheme != null && !uri.scheme.equals("file", true) ||
                    uri.isOpaque ||
                    uri.rawAuthority != null && !uri.host.equals("localhost", true) ||
                    uri.userInfo != null ||
                    uri.port != -1
            )
                return null
            uri.rawPath ?: return null
        }
    val path = decodePath(raw) ?: return null
    if (
        path.isBlank() ||
            path.any { it.isISOControl() } ||
            !windows && pathScheme.containsMatchIn(path)
    )
        return null
    val base = baseDirectory?.takeIf { it.isNotEmpty() } ?: return path
    if (path.startsWith('/') || drivePath.containsMatchIn(path)) return path
    val joined = "${base.trimEnd('/')}/$path"
    return if (base.startsWith('/')) normalizeAbsolutePath(joined) else joined
}

private fun normalizeAbsolutePath(value: String): String {
    val parts = mutableListOf<String>()
    for (part in value.split('/')) {
        when (part) {
            "",
            "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
    }
    return "/" + parts.joinToString("/")
}

private fun decodePath(value: String): String? {
    val encoded = value.encodeToByteArray()
    val bytes = ByteArray(encoded.size)
    var size = 0
    var index = 0
    while (index < encoded.size) {
        if (encoded[index] == '%'.code.toByte()) {
            if (index + 2 >= encoded.size) return null
            val high = encoded[index + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
            val low = encoded[index + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
            bytes[size++] = (high * 16 + low).toByte()
            index += 3
        } else bytes[size++] = encoded[index++]
    }
    return try {
        bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

fun workspaceParentPath(path: String): String {
    val index =
        if (drivePath.containsMatchIn(path)) maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        else path.lastIndexOf('/')
    return when {
        index < 0 -> ""
        index == 0 -> path.substring(0, 1)
        index == 2 && drivePath.containsMatchIn(path) -> path.substring(0, 3)
        else -> path.substring(0, index)
    }
}

/**
 * The page id of a page link: exactly `dsh://open/<id>` (a trailing slash is fine), no user info,
 * port, query or fragment. The bundled guide links the app's pages this way. Null otherwise.
 */
fun pageLinkId(address: String): String? {
    val uri =
        try {
            URI(destination(address))
        } catch (_: URISyntaxException) {
            return null
        }
    if (uri.scheme?.lowercase() != "dsh" || uri.host?.lowercase() != "open") return null
    if (
        uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null
    )
        return null
    return uri.path.orEmpty().split('/').filter { it.isNotEmpty() }.singleOrNull()
}

fun replyLinkTarget(address: String): ReplyLinkTarget {
    val target = destination(address)
    if (target.any { it.isISOControl() }) return ReplyLinkTarget.Unsupported
    pageLinkId(target)?.let {
        return ReplyLinkTarget.Page(it)
    }
    val uri =
        try {
            URI(target)
        } catch (_: URISyntaxException) {
            null
        }
    if (
        uri != null &&
            when (uri.scheme?.lowercase()) {
                "http",
                "https" -> !uri.host.isNullOrBlank()
                "mailto" -> !uri.schemeSpecificPart.isNullOrBlank()
                else -> false
            }
    ) {
        return ReplyLinkTarget.External(target)
    }
    val path = workspaceLinkPath(target) ?: return ReplyLinkTarget.Unsupported
    return ReplyLinkTarget.Workspace(path, bareDomainLink(target))
}

private val hostLabel = Regex("""^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""")
private val ending = Regex("""^[a-z]{2,}$""")
private val fileEndings =
    setOf(
        "md",
        "py",
        "rs",
        "sh",
        "pl",
        "pm",
        "so",
        "cc",
        "ps",
        "ml",
        "mk",
        "sv",
        "tf",
        "bz",
        "gz",
        "xz",
        "js",
        "ts",
        "go",
        "rb",
        "cs",
        "kt",
        "hs",
        "el",
        "ex",
        "fs",
        "jl",
        "db",
        "hh",
        "cp",
        "vb",
        "nb",
        "zip",
        "mov",
        "pro",
    )
private val siteEndings =
    setOf(
        "com",
        "net",
        "org",
        "edu",
        "gov",
        "mil",
        "int",
        "info",
        "biz",
        "dev",
        "app",
        "xyz",
        "top",
        "site",
        "online",
        "tech",
        "cloud",
        "page",
        "blog",
        "shop",
        "store",
        "art",
        "wiki",
        "news",
        "live",
        "life",
        "world",
        "today",
        "space",
        "website",
        "link",
        "club",
        "name",
        "mobi",
        "asia",
        "email",
        "studio",
        "design",
        "media",
        "network",
        "games",
        "chat",
        "fun",
        "icu",
        "vip",
        "ltd",
        "group",
        "global",
        "run",
        "codes",
        "tools",
        "software",
        "one",
        "plus",
        "social",
        "ink",
    )

/** A syntactic website candidate; the Host still gets first chance to resolve it as a file. */
fun bareDomainLink(address: String): String? {
    val target = destination(address)
    if (target.isEmpty() || target.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri =
        try {
            URI(target)
        } catch (_: URISyntaxException) {
            return null
        }
    if (uri.scheme != null || uri.rawAuthority != null) return null
    val host = target.takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
    val labels = host.split('.')
    if (labels.size < 2 || labels.any { !hostLabel.matches(it) }) return null
    val suffix = labels.last()
    if (!ending.matches(suffix) || suffix in fileEndings) return null
    return if (suffix.length == 2 || suffix in siteEndings || labels.first() == "www")
        "https://$target"
    else null
}

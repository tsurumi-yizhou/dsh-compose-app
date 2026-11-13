package dev.fughetta.dshcompose.domain.images

import dev.fughetta.dshcompose.domain.links.workspaceLinkPath
import java.net.URI

sealed interface ReplyImageSource {
    class Web(val address: String) : ReplyImageSource

    class Data(val address: String) : ReplyImageSource

    data class Workspace(val path: String) : ReplyImageSource

    data object Unsupported : ReplyImageSource
}

fun replyImageSource(address: String): ReplyImageSource {
    val target = address.trim().removeSurrounding("<", ">").trim()
    if (target.startsWith("data:", true)) return ReplyImageSource.Data(target)
    if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
        val uri =
            try {
                URI(target.replace(" ", "%20"))
            } catch (_: java.net.URISyntaxException) {
                return ReplyImageSource.Unsupported
            }
        return if (!uri.host.isNullOrBlank() && target.none { it.isISOControl() })
            ReplyImageSource.Web(uri.toASCIIString())
        else ReplyImageSource.Unsupported
    }
    return workspaceLinkPath(target)?.let(ReplyImageSource::Workspace)
        ?: ReplyImageSource.Unsupported
}

package dev.fughetta.dshcompose.links

import android.content.Intent
import android.net.Uri
import dev.fughetta.dshcompose.domain.links.pageLinkId

/**
 * Single external-entry router (docs/architecture.md). Order: deep link -> notification tap ->
 * launcher shortcut -> share sink. UI-free so it is reusable/testable outside the ViewModel.
 */
class IntentRouter(
    private val openPage: (String) -> Boolean,
    private val onUnavailablePage: () -> Unit,
    private val selectSession: (String) -> Unit,
    private val newSession: () -> Unit,
    private val newConversationDraft: (String) -> Unit,
    private val onNotification: (Intent) -> Boolean,
    private val onShortcut: (Intent) -> Boolean,
    private val onShare: (Intent) -> Unit,
) {
    fun handle(intent: Intent) {
        if (routeDeepLink(intent.data)) return
        if (onNotification(intent)) return
        if (onShortcut(intent)) return
        onShare(intent)
    }

    /** Route external `dsh://` deep links; true when handled. */
    fun routeDeepLink(data: Uri?): Boolean {
        if (data == null || data.scheme != "dsh") return false
        when (data.host) {
            "open" -> {
                val id = pageLinkId(data.toString()) ?: return false
                if (openPage(id)) return true
                onUnavailablePage()
                return true
            }
            "session" -> {
                val id =
                    data.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return false
                selectSession(id)
                return true
            }
            "new" -> {
                val text = data.getQueryParameter("text")
                if (text.isNullOrBlank()) newSession() else newConversationDraft(text)
                return true
            }
            else -> return false
        }
    }
}

package dev.fughetta.dshcompose.shortcuts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.fughetta.dshcompose.MainActivity

/**
 * Where the launcher shortcuts (res/xml/shortcuts.xml and the recent conversations) and the Quick
 * Settings tile point: hands the entry to the app's own task as [Shortcuts.ACTION_ENTRY], stamped
 * with [Shortcuts.token].
 *
 * Not exported. Launchers start shortcuts as the publishing app and the tile's PendingIntent is
 * ours, so only those reach it; MainActivity is exported (it is the launcher entry), so the token
 * identifies entries opened through this private Activity.
 */
class EntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = kindOf(intent.action)
        if (kind != null || intent.action == ACTION_OPEN_APP) {
            val forward =
                Intent(this, MainActivity::class.java).apply {
                    if (kind != null) {
                        action = Shortcuts.ACTION_ENTRY
                        putExtra(Shortcuts.EXTRA_KIND, kind)
                        intent.getStringExtra(EXTRA_SESSION)?.let {
                            putExtra(Shortcuts.EXTRA_SESSION, it)
                        }
                        intent.getStringExtra(EXTRA_HOST)?.let {
                            putExtra(Shortcuts.EXTRA_HOST, it)
                        }
                        putExtra(Shortcuts.EXTRA_TOKEN, Shortcuts.token)
                    }
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            try {
                startActivity(forward)
            } catch (_: Exception) {}
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_NEW_CHAT = "dev.fughetta.dshcompose.action.NEW_CHAT"
        const val ACTION_OPEN_CHAT = "dev.fughetta.dshcompose.action.OPEN_CHAT"
        private const val ACTION_OPEN_APP = "dev.fughetta.dshcompose.action.OPEN_APP"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_HOST = "host"

        fun kindOf(action: String?): String? =
            when (action) {
                ACTION_NEW_CHAT -> Shortcuts.KIND_NEW
                ACTION_OPEN_CHAT -> Shortcuts.KIND_CHAT
                else -> null
            }

        fun newChat(context: Context): Intent =
            Intent(context, EntryActivity::class.java).setAction(ACTION_NEW_CHAT)

        fun openApp(context: Context): Intent =
            Intent(context, EntryActivity::class.java).setAction(ACTION_OPEN_APP)

        fun openChat(context: Context, sessionId: String, host: String?): Intent =
            Intent(context, EntryActivity::class.java).setAction(ACTION_OPEN_CHAT).apply {
                putExtra(EXTRA_SESSION, sessionId)
                if (host != null) putExtra(EXTRA_HOST, host)
            }
    }
}

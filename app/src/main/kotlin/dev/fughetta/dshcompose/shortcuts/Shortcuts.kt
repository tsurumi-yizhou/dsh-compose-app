package dev.fughetta.dshcompose.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.icons.LauncherIconViewModel
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Launcher entries delivered directly to the application's state owner. */
class Shortcuts(private val context: Context, private val onEntry: (Map<String, Any?>) -> Unit) {
    data class Recent(val sessionId: String, val title: String, val host: String?)

    fun publish(recents: List<Recent>): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        return try {
            val room =
                (manager.maxShortcutCountPerActivity - STATIC_SHORTCUTS).coerceIn(0, MAX_RECENT)
            val entry = LauncherIconViewModel.entry(context)
            val shortcuts =
                recents.take(room).mapIndexed { rank, recent ->
                    chat(
                        context,
                        RECENT_PREFIX + recent.sessionId,
                        ShortcutLabels.short(recent.title),
                        ShortcutLabels.long(recent.title),
                        EntryActivity.openChat(context, recent.sessionId, recent.host),
                        rank,
                        entry,
                    )
                }
            if (shortcuts.isEmpty()) {
                manager.removeAllDynamicShortcuts()
                true
            } else {
                // Rate-limited while the app is in the background: false, and the next change
                // made in the foreground catches up.
                manager.setDynamicShortcuts(shortcuts)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun handleIntent(intent: Intent): Boolean {
        if (
            intent.getBooleanExtra(EXTRA_TAKEN, false) ||
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        )
            return false
        return take(intent).also { if (it) intent.putExtra(EXTRA_TAKEN, true) }
    }

    /** Delivers an entry; false when [intent] isn't one of ours. */
    private fun take(intent: Intent): Boolean {
        if (intent.action != ACTION_ENTRY) return false
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return true
        // Consumed either way: an entry without our token goes nowhere.
        if (intent.getStringExtra(EXTRA_TOKEN) != token) return true
        if (kind != KIND_NEW && kind != KIND_CHAT) return true
        val sessionId = intent.getStringExtra(EXTRA_SESSION)
        if (kind == KIND_CHAT && sessionId.isNullOrEmpty()) return true
        val event =
            mapOf(
                "kind" to kind,
                "sessionId" to sessionId,
                "host" to intent.getStringExtra(EXTRA_HOST),
            )
        onEntry(event)
        return true
    }

    companion object {
        const val ACTION_ENTRY = "dev.fughetta.dshcompose.action.ENTRY"
        const val EXTRA_KIND = "dev.fughetta.dshcompose.entry.KIND"
        const val EXTRA_SESSION = "dev.fughetta.dshcompose.entry.SESSION"
        const val EXTRA_HOST = "dev.fughetta.dshcompose.entry.HOST"
        const val EXTRA_TOKEN = "dev.fughetta.dshcompose.entry.TOKEN"
        private const val EXTRA_TAKEN = "dev.fughetta.dshcompose.entry.TAKEN"

        const val KIND_NEW = "new"
        const val KIND_CHAT = "chat"

        const val MAX_RECENT = 3
        const val RECENT_PREFIX = "chat:"

        /** 新对话 and 语音输入 in res/xml/shortcuts.xml. */
        private const val STATIC_SHORTCUTS = 2

        /** Per process, never leaves it: proves an entry came through [EntryActivity]. */
        val token: String by lazy { UUID.randomUUID().toString() }

        /**
         * ShortcutManager and launcher-entry changes are binder calls: off the main thread, and one
         * at a time (a new 桌面图标 and a new recents list must not interleave).
         */
        internal val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dsh-shortcuts").apply { isDaemon = true }
        }

        private fun chat(
            context: Context,
            id: String,
            short: CharSequence,
            long: CharSequence?,
            intent: Intent,
            rank: Int,
            entry: ComponentName?,
        ): ShortcutInfo =
            ShortcutInfo.Builder(context, id)
                .setShortLabel(short)
                .apply { if (!long.isNullOrEmpty()) setLongLabel(long) }
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_chat))
                .setIntent(intent)
                .setRank(rank)
                .apply { if (entry != null) setActivity(entry) }
                .build()

        /**
         * Re-publishes the recent conversations under [entry], the launcher alias just switched on
         * (before the old one goes off: the system deletes dynamic shortcuts whose activity is no
         * longer a launcher entry). Pinned copies stay pinned. On [worker].
         */
        fun rebind(context: Context, entry: ComponentName): Boolean {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
            return try {
                val current = manager.dynamicShortcuts
                if (current.isEmpty()) return true
                val moved =
                    current
                        .sortedBy { it.rank }
                        .mapNotNull { old ->
                            val intent = old.intent ?: return@mapNotNull null
                            val short = old.shortLabel ?: return@mapNotNull null
                            chat(context, old.id, short, old.longLabel, intent, old.rank, entry)
                        }
                manager.setDynamicShortcuts(moved)
            } catch (_: Exception) {
                false
            }
        }
    }
}

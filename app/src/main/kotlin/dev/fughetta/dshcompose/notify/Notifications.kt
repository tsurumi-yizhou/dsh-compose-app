package dev.fughetta.dshcompose.notify

import android.content.Context
import android.content.Intent

/** Process-owned notifications; taps and approval actions go directly to native state. */
class Notifications(
    private val context: Context,
    private val onTap: (Map<String, Any?>) -> Unit,
    private val onAction: ((Map<String, Any?>) -> Boolean)? = null,
) : AutoCloseable {
    val notifier = ChatNotifier(context)

    init {
        instance = this
        LiveActivity.refresh(context)
    }

    fun handleIntent(intent: Intent): Boolean {
        val tap = notifier.takeTap(intent) ?: return false
        onTap(tap)
        return true
    }

    fun openSettings(channel: String? = null): Boolean =
        start(listOf(notifier.settingsIntent(channel), notifier.detailsIntent()))

    fun openLiveSettings(): Boolean =
        start(
            listOf(
                LiveActivity.settingsIntent(context),
                notifier.settingsIntent(null),
                notifier.detailsIntent(),
            ),
        )

    /**
     * Where Samsung's Now Bar admits apps: Developer options → 「所有应用程序的实时通知」. Older builds without
     * that page fall back to this app's notification settings, which is where the reader would look
     * next.
     */
    fun openNowBarSettings(): Boolean =
        start(
            listOf(
                NowBar.developerSettingsIntent(),
                notifier.settingsIntent(null),
                notifier.detailsIntent(),
            ),
        )

    private fun start(intents: List<Intent>): Boolean = intents.any {
        runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    override fun close() {
        if (instance === this) instance = null
    }

    companion object {
        @Volatile private var instance: Notifications? = null

        fun dispatchAction(action: Map<String, Any?>): Boolean {
            val target = instance ?: return false
            return runCatching { target.onAction?.invoke(action) == true }.getOrDefault(false)
        }
    }
}

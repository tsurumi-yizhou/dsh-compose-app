package dev.fughetta.dshcompose.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The approval buttons on chat notifications and 「停止」 on the live card (not exported: only our own
 * PendingIntents reach it).
 *
 * A tapped button finds no controller when the process that posted the card is gone — the card
 * itself times out then — and does nothing.
 */
class NotifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LiveActivity.ACTION_STOP) {
            val host = intent.getStringExtra(LiveActivity.EXTRA_HOST) ?: return
            val session = intent.getStringExtra(LiveActivity.EXTRA_SESSION) ?: return
            Notifications.dispatchAction(
                mapOf("action" to "stop", "host" to host, "session" to session),
            )
            return
        }
        ChatNotifier(context.applicationContext).receiveAction(intent)
    }
}

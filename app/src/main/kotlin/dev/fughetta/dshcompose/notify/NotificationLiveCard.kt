package dev.fughetta.dshcompose.notify

import android.content.Context
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.i18n.AppStrings
import kotlinx.coroutines.*

internal fun notificationWords(app: Context): NotificationWords =
    NotificationWords(
        untitled = AppStrings.get(app, UiR.string.notification_controls_untitled),
        publicDone = AppStrings.get(app, UiR.string.notification_controls_public_done),
        publicApproval = AppStrings.get(app, UiR.string.notification_controls_public_approval),
        publicQuestion = AppStrings.get(app, UiR.string.notification_controls_public_question),
        publicStopped = AppStrings.get(app, UiR.string.notification_controls_public_stopped),
        publicFailed = AppStrings.get(app, UiR.string.notification_controls_public_failed),
        chipDone = AppStrings.get(app, UiR.string.notification_controls_chip_done),
        chipFailed = AppStrings.get(app, UiR.string.notification_controls_chip_failed),
        chipWaiting = AppStrings.get(app, UiR.string.notification_controls_chip_waiting),
        chipRunning = AppStrings.get(app, UiR.string.notification_controls_chip_running),
        livePublic = AppStrings.get(app, UiR.string.notification_controls_live_public),
        runningText = { others ->
            if (others > 0)
                AppStrings.get(app, UiR.string.notification_controls_live_running) +
                    " · " +
                    AppStrings.get(app, UiR.string.notification_controls_live_more, others)
            else AppStrings.get(app, UiR.string.notification_controls_live_running)
        },
    )

internal fun NotificationsViewModel.updateLive() {
    if (closed || liveJob?.isActive == true) return
    liveJob = scope.launch {
        delay(150)
        val preferences = mutable.value
        val pending =
            hosts.values
                .asSequence()
                .flatMap { it.requests.values.asSequence() }
                .firstOrNull {
                    current(it) &&
                        it.alert != null &&
                        allows(if (it.approval) "approval" else "question")
                }
        val running =
            hosts.values
                .asSequence()
                .filter { it.sessions.generation != null }
                .flatMap { host ->
                    host.sessions.running.entries
                        .asSequence()
                        .filter { !skipSession(host.id, host.sessions.root(it.key)) }
                        .map { Triple(host, it.key, it.value) }
                }
                .firstOrNull()
        val content =
            pending?.alert?.let { liveContent(it, LiveContent.Kind.WAITING) }
                ?: ending
                ?: running?.let { (host, id, run) ->
                    val root = host.sessions.root(id)
                    NotificationCopy.runningContent(
                        title = title(host, root),
                        text = runningText(),
                        since = run.since,
                        sessionId = root,
                        host = host.id,
                        words = words,
                    )
                }
        LiveActivity.update(app, preferences.enabled && preferences.live, content)
    }
}

/**
 * The running card's line: 「正在处理对话」, and how many other conversations run when there are — one card
 * speaks for all of them.
 */
private fun NotificationsViewModel.runningText(): String {
    val others =
        hosts.values
            .flatMap { host -> host.sessions.running.keys.map { host.sessions.root(it) } }
            .distinct()
            .size - 1
    return words.runningText(others)
}

internal fun notificationCompletionText(
    app: Context,
    outcome: dev.fughetta.dshcompose.domain.notify.NotificationTurn,
): String =
    when (outcome.kind) {
        "completed" ->
            outcome.reply?.let {
                AppStrings.get(app, UiR.string.notification_controls_done_reply, it)
            } ?: AppStrings.get(app, UiR.string.notification_controls_done)
        "max-tokens" -> AppStrings.get(app, UiR.string.notification_controls_output_limit)
        "aborted" -> AppStrings.get(app, UiR.string.notification_controls_stopped)
        "blocked" -> AppStrings.get(app, UiR.string.notification_controls_blocked)
        "interrupted" -> AppStrings.get(app, UiR.string.notification_controls_interrupted)
        else ->
            AppStrings.get(
                app,
                if (outcome.code == "MISSING_CREDENTIAL")
                    UiR.string.notification_controls_missing_key
                else UiR.string.notification_controls_failed,
            )
    }

internal fun notificationFailureText(app: Context, code: String): String =
    AppStrings.get(
        app,
        when (code) {
            "notify/save" -> UiR.string.notification_controls_save_failed
            "notify/settings" -> UiR.string.notification_controls_settings_failed
            "notify/answer" -> UiR.string.notification_controls_answer_failed
            "notify/stop" -> UiR.string.notification_controls_stop_failed
            "notify/post" -> UiR.string.notification_controls_post_failed
            "notify/nowbar" -> UiR.string.notification_controls_nowbar_failed
            else -> UiR.string.notification_controls_read_failed
        },
    )

package dev.fughetta.dshcompose.notify

/** The words the notification surface uses; mapped once so the mapping is unit-testable. */
internal class NotificationWords(
    val untitled: String,
    val publicDone: String,
    val publicApproval: String,
    val publicQuestion: String,
    val publicStopped: String,
    val publicFailed: String,
    val chipDone: String,
    val chipFailed: String,
    val chipWaiting: String,
    val chipRunning: String,
    val livePublic: String,
    val runningText: (Int) -> String,
)

internal object NotificationCopy {
    fun publicText(kind: String, words: NotificationWords): String =
        when (kind) {
            "done" -> words.publicDone
            "approval" -> words.publicApproval
            "question" -> words.publicQuestion
            "stopped" -> words.publicStopped
            else -> words.publicFailed
        }

    fun title(cached: String?, words: NotificationWords): String =
        cached?.takeIf { it.isNotBlank() } ?: words.untitled

    fun liveContent(
        kind: LiveContent.Kind,
        title: String,
        text: String,
        publicText: String,
        sessionId: String?,
        host: String?,
        eventId: String?,
        words: NotificationWords,
    ): LiveContent =
        LiveContent(
            kind,
            title.take(LiveContent.TITLE_MAX),
            text.take(LiveContent.TEXT_MAX),
            chip(kind, words),
            publicText,
            sessionId = sessionId,
            host = host,
            eventId = eventId,
        )

    fun runningContent(
        title: String,
        text: String,
        since: Long,
        sessionId: String,
        host: String,
        words: NotificationWords,
    ): LiveContent =
        LiveContent(
            LiveContent.Kind.RUNNING,
            title.take(LiveContent.TITLE_MAX),
            text,
            words.chipRunning,
            words.livePublic,
            since,
            sessionId,
            host,
        )

    private fun chip(kind: LiveContent.Kind, words: NotificationWords): String? =
        when (kind) {
            LiveContent.Kind.DONE -> words.chipDone
            LiveContent.Kind.FAILED -> words.chipFailed
            LiveContent.Kind.WAITING -> words.chipWaiting
            else -> words.chipRunning
        }
}

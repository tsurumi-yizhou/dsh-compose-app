package dev.fughetta.dshcompose.domain.messages

/**
 * Decides whether a send can be queued while no link is up. Pure so the rules stay testable: only
 * plain text (optionally quoted) can wait locally — attachments, media generation headers, slash
 * commands and a not-yet-created conversation all need the engine.
 */
object OfflineSendPolicy {
    enum class Rejection {
        /** A draft conversation has no session id until the engine creates one. */
        DraftSession,

        /** The chat is already sending, syncing or receiving a turn. */
        Busy,

        /** Nothing to queue. */
        Empty,

        /** Attachments must be uploaded to the engine first. */
        Attachments,

        /** Media generation headers are engine parameters. */
        MediaGeneration,

        /** The engine home is not known yet, so the prompt cannot be addressed. */
        UnknownHome,
    }

    fun rejection(
        draftSession: Boolean,
        sending: Boolean,
        syncing: Boolean,
        running: Boolean,
        draft: String,
        quote: String?,
        attachmentCount: Int,
        mediaGeneration: Boolean,
        home: String?,
    ): Rejection? =
        when {
            draftSession -> Rejection.DraftSession
            sending || syncing || running -> Rejection.Busy
            draft.isBlank() && quote.isNullOrBlank() -> Rejection.Empty
            attachmentCount > 0 -> Rejection.Attachments
            mediaGeneration -> Rejection.MediaGeneration
            home.isNullOrEmpty() -> Rejection.UnknownHome
            else -> null
        }
}

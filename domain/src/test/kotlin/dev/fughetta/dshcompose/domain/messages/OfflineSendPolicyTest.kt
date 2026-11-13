package dev.fughetta.dshcompose.domain.messages

import org.junit.Assert.*
import org.junit.Test

class OfflineSendPolicyTest {
    private fun rejection(
        draftSession: Boolean = false,
        sending: Boolean = false,
        syncing: Boolean = false,
        running: Boolean = false,
        draft: String = "hello",
        quote: String? = null,
        attachmentCount: Int = 0,
        mediaGeneration: Boolean = false,
        home: String? = "/home/user/.dsh",
    ) =
        OfflineSendPolicy.rejection(
            draftSession,
            sending,
            syncing,
            running,
            draft,
            quote,
            attachmentCount,
            mediaGeneration,
            home,
        )

    @Test
    fun plainTextWithAKnownHomeIsQueued() {
        assertNull(rejection())
        assertNull(rejection(quote = "quoted line"))
    }

    @Test
    fun aDraftConversationCannotBeQueued() {
        assertEquals(OfflineSendPolicy.Rejection.DraftSession, rejection(draftSession = true))
    }

    @Test
    fun aBusyConversationCannotBeQueued() {
        assertEquals(OfflineSendPolicy.Rejection.Busy, rejection(sending = true))
        assertEquals(OfflineSendPolicy.Rejection.Busy, rejection(syncing = true))
        assertEquals(OfflineSendPolicy.Rejection.Busy, rejection(running = true))
    }

    @Test
    fun anEmptyComposerHasNothingToQueue() {
        assertEquals(OfflineSendPolicy.Rejection.Empty, rejection(draft = "   "))
    }

    @Test
    fun attachmentsAndMediaGenerationNeedTheEngine() {
        assertEquals(OfflineSendPolicy.Rejection.Attachments, rejection(attachmentCount = 1))
        assertEquals(OfflineSendPolicy.Rejection.MediaGeneration, rejection(mediaGeneration = true))
    }

    @Test
    fun anUnknownHomeBlocksQueueing() {
        assertEquals(OfflineSendPolicy.Rejection.UnknownHome, rejection(home = null))
        assertEquals(OfflineSendPolicy.Rejection.UnknownHome, rejection(home = ""))
    }
}

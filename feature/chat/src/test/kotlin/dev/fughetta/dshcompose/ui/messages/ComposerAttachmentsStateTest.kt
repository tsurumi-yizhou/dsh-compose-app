package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.domain.attachments.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ComposerAttachmentsStateTest {
    private fun file(id: String, bytes: Long = 1) =
        ComposerFile(id, id, "text/plain", (File(id)).absolutePath, bytes)

    @Test
    fun promotionAndHandoffPreserveIndependentImmutableSelections() {
        val state = ComposerAttachmentsState()
        val sent = file("sent")
        val remaining = file("remaining")
        state.add("draft", listOf(sent, remaining))
        val before = state.state.value
        state.move("draft", "conversation")
        assertTrue(state.selected("draft").isEmpty())
        assertEquals(listOf(sent), state.detach("conversation", setOf(sent.id)))
        assertEquals(listOf(remaining), state.selected("conversation"))
        assertEquals(listOf(sent, remaining), before["draft"])
        assertFalse(before.containsKey("conversation"))
        state.add("other", listOf(sent))
        assertEquals(listOf(remaining), state.take("conversation"))
        assertEquals(listOf(sent), state.clear())
        assertTrue(state.state.value.isEmpty())
    }

    @Test
    fun rejectedRestorationOrPromotionLeavesAllExistingSelectionsIntact() {
        val state = ComposerAttachmentsState()
        state.add("destination", (1..9).map { file("file-$it") })
        state.add("draft", listOf(file("draft")))
        val before = state.state.value
        try {
            state.move("draft", "destination")
            fail("Full composer cannot accept another selection")
        } catch (error: AttachmentImportException) {
            assertEquals("too_many", error.code)
        }
        assertSame(before, state.state.value)
        state.add("large", listOf(file("large", AttachmentLimits.MAX_TOTAL_BYTES)))
        val full = state.state.value
        try {
            state.add("large", listOf(file("restored")))
            fail("Restoration shares the import budget")
        } catch (error: AttachmentImportException) {
            assertEquals("too_large", error.code)
        }
        assertSame(full, state.state.value)
    }
}

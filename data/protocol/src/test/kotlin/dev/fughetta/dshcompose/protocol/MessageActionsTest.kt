package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.messages.QuotedText
import dev.fughetta.dshcompose.domain.messages.normalizeQuote
import dev.fughetta.dshcompose.domain.messages.splitQuote
import dev.fughetta.dshcompose.domain.messages.withQuote
import org.junit.Assert.*
import org.junit.Test

class MessageActionsTest {
    private fun event(seq: Long, type: String, data: String = "{}") =
        SessionRecord(seq, seq, type, parseObject(data))

    @Test
    fun appendOnlyMessageActionsCutOnlyKnownFinishedPrefixesAndPreserveQuotedText() {
        val promptLead =
            listOf(
                event(9, "turn/end"),
                event(10, "agent/inbox/spliced"),
                event(11, "turn/start"),
                event(12, "step/start"),
                event(13, "system/message"),
            )
        assertEquals(9L, forkPointBeforePrompt(promptLead.asReversed(), 14))
        assertNull(forkPointBeforePrompt(promptLead.dropLast(1).asReversed(), 14))
        val reply =
            event(
                20,
                "assistant/message",
                """{"message":{"content":[{"type":"text","text":"Done"}]}}""",
            )
        assertEquals(
            22L,
            forkPointAfterReply(
                listOf(
                    reply,
                    event(21, "step/end"),
                    event(22, "turn/end", """{"reason":{"kind":"completed"}}"""),
                ),
                20,
            ),
        )
        assertNull(
            forkPointAfterReply(listOf(reply, event(21, "tool/call"), event(22, "turn/end")), 20),
        )
        assertNull(
            forkPointAfterReply(
                listOf(reply, event(21, "turn/end", """{"reason":{"kind":"error"}}""")),
                20,
            ),
        )
        val sent = withQuote("line 1\r\n\r\nline 2", "Follow up")
        assertEquals(QuotedText("line 1\n\nline 2", "Follow up"), splitQuote(sent))
        val clipped = normalizeQuote("😀".repeat(1300))
        assertEquals(1201, clipped.codePointCount(0, clipped.length))
        assertTrue(clipped.endsWith("…"))
    }
}

package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.transcript.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatMessageOrderTest {
    @Test
    fun transcriptOrderSnapshotsSurviveToolUpdatesDraftInsertionHistoryPagingAndReplacement() {
        val transcript = ChatTranscript()
        fun record(seq: Long, type: String, data: String) =
            SessionRecord(seq, seq, type, Json.parseToJsonElement(data).jsonObject)
        fun apply(seq: Long, type: String, data: String) =
            transcript.apply(SessionUpdate.Record(record(seq, type, data)))
        apply(
            10,
            "assistant/message",
            """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"View"}]}}""",
        )
        val original = transcript.presentation
        assertEquals(ChatFollowingMessage(true), original.order.following(original.items.single()))
        apply(11, "tool/call", """{"callId":"call","name":"read","arguments":"{}"}""")
        assertSame(original.order, transcript.presentation.order)
        transcript.apply(
            SessionUpdate.Assistant(
                Json.parseToJsonElement(
                        """{"type":"start","attemptId":"draft","turn":2,"step":1,"startedAfterSeq":11}""",
                    )
                    .jsonObject,
            ),
        )
        transcript.apply(
            SessionUpdate.Assistant(
                Json.parseToJsonElement(
                        """{"type":"chunk","attemptId":"draft","index":0,"chunk":{"type":"text-delta","index":0,"text":"New view"}}""",
                    )
                    .jsonObject,
            ),
        )
        val draft = transcript.presentation
        assertTrue(draft.order.following(original.items.single()).laterAssistant)
        assertTrue(draft.order.following(draft.items.last()).current)
        assertFalse(draft.order.following(draft.items.last()).laterAssistant)
        assertFalse(original.order.following(original.items.single()).laterAssistant)
        apply(
            12,
            "user/message",
            """{"source":{"kind":"user"},"content":[{"type":"text","text":"Answer"}]}""",
        )
        assertEquals("Answer", transcript.presentation.order.following(draft.items.last()).userText)
        transcript.prependHistory(
            listOf(
                record(
                    1,
                    "assistant/message",
                    """{"turn":0,"step":1,"message":{"content":[{"type":"text","text":"Older"}]}}""",
                ),
            ),
            false,
        )
        val paged = transcript.presentation
        assertEquals("Answer", paged.order.following(paged.items.first()).userText)
        assertFalse(original.order.following(paged.items.first()).current)
        apply(
            10,
            "assistant/message",
            """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"Replaced"}]}}""",
        )
        assertEquals(
            "Answer",
            transcript.presentation.order.following(original.items.single()).userText,
        )
    }
}

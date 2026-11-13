package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TodosTest {
    private fun event(seq: Long, type: String, data: String = "{}") =
        SessionRecord(seq, seq * 1000, type, parseObject(data))

    private fun snapshot(
        cursor: Long,
        projection: String,
        records: List<SessionRecord> = emptyList(),
        replaced: Boolean = false,
    ) =
        SessionUpdate.Snapshot(
            ConnectionGeneration(1, "client", "/work"),
            cursor,
            records,
            true,
            JsonObject(emptyMap()),
            parseObject(projection),
            null,
            replaced,
        )

    @Test
    fun projectedTodosSurvivePagingAndStreamingUntilTheNextTurnAndRebuildInSequenceOrder() {
        val transcript = ChatTranscript()
        val stale =
            event(
                101,
                "todo/write",
                """{"todos":[{"content":"Historical","status":"completed"}]}""",
            )
        transcript.apply(
            snapshot(
                101,
                """{"asOfSeq":101,"values":{"todos":[
            {"content":"Read","status":"completed"},{"content":"Test","status":"pending"},{"content":"Fix","status":"in_progress"}]}}""",
                listOf(event(100, "turn/start"), stale),
            ),
        )
        val initial = transcript.todos!!
        assertEquals(3, initial.total)
        assertEquals(1, initial.done)
        assertEquals(2, initial.currentIndex)
        assertEquals("Fix", initial.current!!.content)
        transcript.apply(
            SessionUpdate.Assistant(
                parseObject(
                    """{"type":"start","attemptId":"stream","turn":1,"step":1,"startedAfterSeq":101}""",
                ),
            ),
        )
        repeat(1000) { index ->
            transcript.apply(
                SessionUpdate.Assistant(
                    parseObject(
                        """{"type":"chunk","attemptId":"stream","index":$index,"chunk":{"type":"text-delta","index":0,"text":"x"}}""",
                    ),
                ),
            )
            assertSame(initial, transcript.todos)
        }
        transcript.prependHistory(
            listOf(
                event(
                    1,
                    "todo/write",
                    """{"todos":[{"content":"Obsolete","status":"in_progress"}]}""",
                ),
                event(2, "turn/start"),
            ),
            true,
        )
        assertSame(initial, transcript.todos)
        transcript.apply(
            SessionUpdate.Record(
                event(
                    102,
                    "todo/write",
                    """{"todos":[{"content":"Read","status":"completed"},{"content":"Test","status":"pending"}]}""",
                ),
            ),
        )
        val next = transcript.todos!!
        assertEquals("Test", next.current!!.content)
        assertEquals(1, next.currentIndex)
        assertEquals(3, initial.total)
        assertEquals("Fix", initial.current!!.content)
        transcript.apply(
            SessionUpdate.Record(
                event(
                    104,
                    "todo/write",
                    """{"todos":[{"content":"Complete","status":"completed"}]}""",
                ),
            ),
        )
        transcript.apply(
            SessionUpdate.Record(
                event(
                    103,
                    "todo/write",
                    """{"todos":[{"content":"Late old event","status":"pending"}]}""",
                ),
            ),
        )
        val completed = transcript.todos!!
        assertTrue(completed.allDone)
        assertEquals(-1, completed.currentIndex)
        assertNull(completed.current)
        transcript.apply(SessionUpdate.Record(event(105, "turn/end")))
        assertSame(completed, transcript.todos)
        val history =
            (3L until 100).map {
                event(it, "todo/write", """{"todos":[{"content":"Old $it","status":"pending"}]}""")
            }
        transcript.prependHistory(history, false)
        assertSame(completed, transcript.todos)
        transcript.apply(SessionUpdate.Record(event(106, "turn/start")))
        assertNull(transcript.todos)
        transcript.apply(SessionUpdate.Record(event(107, "todo/write", """{"todos":[]}""")))
        assertEquals(0, transcript.todos!!.total)
        transcript.apply(snapshot(200, """{"asOfSeq":200,"values":{"todos":null}}"""))
        assertNull(transcript.todos)
        transcript.apply(
            SessionUpdate.Record(
                event(
                    201,
                    "todo/write",
                    """{"todos":[{"content":"Restored","status":"pending"}]}""",
                ),
            ),
        )
        assertEquals("Restored", transcript.todos!!.current!!.content)
        transcript.apply(
            snapshot(
                10,
                """{"asOfSeq":10,"values":{"todos":[{"content":"Replaced","status":"pending"}]}}""",
                replaced = true,
            ),
        )
        assertEquals("Replaced", transcript.todos!!.current!!.content)
        transcript.prependHistory(
            listOf(
                event(
                    5,
                    "todo/write",
                    """{"todos":[{"content":"Old branch","status":"completed"}]}""",
                ),
            ),
            false,
        )
        assertEquals("Replaced", transcript.todos!!.items.single().content)
    }
}

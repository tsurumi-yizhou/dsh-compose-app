package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.messages.InboxSlot
import org.junit.Assert.*
import org.junit.Test

class InboxProjectionTest {
    private fun event(seq: Long, data: String) =
        SessionRecord(seq, seq, "agent/inbox/spliced", parseObject(data))

    @Test
    fun mixedContentKeepsItsEditabilityAndContextClassificationAfterDecoding() {
        val projection = InboxProjection()
        projection.seed(
            parseObject(
                """{"asOfSeq":0,"values":{"inbox":{"next-turn":[
            {"id":"user","source":{"kind":"user","rpcId":"request","form":"prompt"},"content":[
                {"type":"text","text":"First"},{"type":"image","attachmentId":"image"},{"type":"text","text":"Last"}]},
            {"id":"context","source":{"kind":"goal"},"content":[{"type":"text","text":"Context"}]}
        ]}}}""",
            ),
        )
        projection.rebuild(emptyList())
        val user = projection.current.first()
        assertEquals("First\nLast", user.text)
        assertEquals("request", user.requestId)
        assertEquals("prompt", user.sourceForm)
        assertFalse(user.textOnly)
        assertFalse(user.automatic)
        assertTrue(projection.current.last().hidden)
        assertTrue(projection.current.last().automatic)
        projection.apply(
            event(
                1,
                """{"target":"next-turn","removedCount":1,"inserted":[
            {"id":"user","source":{"kind":"user"},"content":[{"type":"text","text":"Edited"}]}
        ]}""",
            ),
        )
        assertEquals("Edited", projection.current.first().text)
        assertTrue(projection.current.first().textOnly)
    }

    @Test
    fun authoritativeInboxSurvivesHistoryRebuildAndTracksRealQueueSplices() {
        val projection = InboxProjection()
        projection.seed(
            parseObject(
                """{"asOfSeq":100,"values":{"inbox":{"next-turn":[{"id":"existing","source":{"kind":"user","rpcId":"r1"},"content":[{"type":"text","text":"Waiting"}]}]}}}""",
            ),
        )
        val old =
            event(
                50,
                """{"target":"next-turn","start":0,"removedCount":0,"inserted":[{"id":"old","content":[],"source":{"kind":"user"}}]}""",
            )
        projection.rebuild(listOf(old))
        assertEquals(listOf("existing"), projection.current.map { it.id })
        val unchanged = projection.current
        projection.apply(old)
        assertSame(unchanged, projection.current)
        val steer =
            event(
                101,
                """{"target":"next-step","start":0,"removedCount":0,"inserted":[{"id":"steer","source":{"kind":"user","rpcId":"r2"},"content":[{"type":"text","text":"Steer now"}]}]}""",
            )
        projection.apply(steer)
        assertEquals(listOf("steer", "existing"), projection.current.map { it.id })
        assertEquals(InboxSlot.NextStep, projection.current.first().slot)
        projection.apply(
            event(102, """{"target":"next-turn","start":0,"removedCount":1,"inserted":[]}"""),
        )
        assertEquals(listOf("steer"), projection.current.map { it.id })
        projection.rebuild(
            listOf(
                old,
                steer,
                event(102, """{"target":"next-turn","start":0,"removedCount":1,"inserted":[]}"""),
            ),
        )
        assertEquals(listOf("steer"), projection.current.map { it.id })
        assertFalse(projection.current.first().automatic)
    }

    @Test
    fun spliceRecordsMayOmitZeroedFields() {
        val projection = InboxProjection()
        projection.seed(parseObject("""{"asOfSeq":0,"values":{"inbox":{}}}"""))
        // The engine's insert-only record carries no `removedCount`; `start` may be absent too.
        projection.apply(
            event(
                1,
                """{"target":"next-turn","start":0,"inserted":[{"id":"a","content":[],"source":{"kind":"user"}}]}""",
            ),
        )
        projection.apply(
            event(
                2,
                """{"target":"next-turn","inserted":[{"id":"b","content":[],"source":{"kind":"user"}}]}""",
            ),
        )
        assertEquals(listOf("b", "a"), projection.current.map { it.id })
        projection.apply(event(3, """{"target":"next-turn","removedCount":2}"""))
        assertEquals(emptyList<String>(), projection.current.map { it.id })
    }
}

package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.protocol.SessionRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class TranscriptSyncTest {
    private val host = "host"

    private fun record(seq: Long, type: String = "user/message", text: String = "m$seq") =
        SessionRecord(seq, seq * 10, type, buildJsonObject { put("text", text) })

    @Test
    fun appendStoresTheRecordsAndAdvancesTheCursor() = runTest {
        val transcripts = FakeTranscripts()
        val cursors = FakeCursors()
        val sync = TranscriptSync(transcripts, cursors, clock = { 1_000L })

        assertEquals(null, sync.cursor(host, "s"))
        assertEquals(3, sync.append(host, "s", listOf(record(1), record(2), record(3))))

        assertEquals(3, transcripts.storage.size)
        assertEquals(3L, sync.cursor(host, "s"))
        val row = transcripts.storage.getValue("$host/s/3")
        assertEquals("user/message", row.kind)
        assertEquals(30, row.recordedAt)
        assertEquals("""{"text":"m3"}""", row.payload)
    }

    @Test
    fun anEmptyBatchLeavesTheMirrorAndCursorAlone() = runTest {
        val transcripts = FakeTranscripts()
        val cursors = FakeCursors()
        val sync = TranscriptSync(transcripts, cursors, clock = { 1_000L })
        sync.append(host, "s", listOf(record(5)))

        assertEquals(0, sync.append(host, "s", emptyList()))
        assertEquals(1, transcripts.storage.size)
        assertEquals(5L, sync.cursor(host, "s"))
    }

    @Test
    fun reReadingTheSamePageIsIdempotent() = runTest {
        val transcripts = FakeTranscripts()
        val cursors = FakeCursors()
        val sync = TranscriptSync(transcripts, cursors, clock = { 1_000L })
        sync.append(host, "s", listOf(record(1), record(2)))

        sync.append(host, "s", listOf(record(1, text = "edited"), record(2)))

        assertEquals(2, transcripts.storage.size)
        assertEquals("""{"text":"edited"}""", transcripts.storage.getValue("$host/s/1").payload)
    }

    @Test
    fun anOlderPageNeverMovesTheCursorBackwards() = runTest {
        val transcripts = FakeTranscripts()
        val cursors = FakeCursors()
        val sync = TranscriptSync(transcripts, cursors, clock = { 1_000L })
        sync.append(host, "s", (1L..5L).map { record(it) })

        sync.append(host, "s", listOf(record(1), record(2)))

        assertEquals(5L, sync.cursor(host, "s"))
        assertEquals(5, transcripts.storage.size)
    }

    @Test
    fun sessionsAndHostsKeepSeparateCursors() = runTest {
        val transcripts = FakeTranscripts()
        val cursors = FakeCursors()
        val sync = TranscriptSync(transcripts, cursors, clock = { 1_000L })

        sync.append(host, "a", listOf(record(7)))
        sync.append(host, "b", listOf(record(3)))
        sync.append("other", "a", listOf(record(9)))

        assertEquals(7L, sync.cursor(host, "a"))
        assertEquals(3L, sync.cursor(host, "b"))
        assertEquals(9L, sync.cursor("other", "a"))
    }

    @Test
    fun cachedReturnsTheNewestRecordsInAscendingOrder() = runTest {
        val transcripts = FakeTranscripts()
        val sync = TranscriptSync(transcripts, FakeCursors(), clock = { 1_000L })
        sync.append(host, "s", (1L..10L).map { record(it) })

        val page = sync.cached(host, "s", limit = 3)

        assertEquals(listOf(8L, 9L, 10L), page.map { it.seq })
    }

    @Test
    fun theMirrorKeepsOnlyTheNewestRecordsPerSession() = runTest {
        val transcripts = FakeTranscripts()
        val cursors = FakeCursors()
        val sync = TranscriptSync(transcripts, cursors, clock = { 1_000L }, keep = 3)

        sync.append(host, "s", (1L..5L).map(::record))

        assertEquals(listOf(3L, 4L, 5L), sync.cached(host, "s", limit = 10).map { it.seq })
        assertEquals(5L, sync.cursor(host, "s"))
    }

    @Test
    fun pruningLeavesTheCursorAndTheRemainingTailConsistent() = runTest {
        val transcripts = FakeTranscripts()
        val sync = TranscriptSync(transcripts, FakeCursors(), clock = { 1_000L }, keep = 3)
        sync.append(host, "s", (1L..6L).map(::record))

        // The newest three stay readable, the cursor still points at the newest seq, and no page
        // starts below the pruned boundary.
        assertEquals(listOf(4L, 5L, 6L), sync.cached(host, "s", limit = 10).map { it.seq })
        assertEquals(6L, sync.cursor(host, "s"))
        assertEquals(emptyList<Long>(), sync.older(host, "s", 4L, 10).map { it.seq })
    }
}

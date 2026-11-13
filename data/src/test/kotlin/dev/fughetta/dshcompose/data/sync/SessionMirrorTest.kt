package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.protocol.SessionRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class SessionMirrorTest {
    private val host = "host"

    private class Fixture {
        val sessions = FakeSessionMirror()
        val cursors = FakeCursors()
        val transcripts = FakeTranscripts()
        val outbox = FakeOutbox()
        val mirror =
            SessionMirror(
                LocalSessionCatalog(sessions, SessionDirectorySync(sessions, cursors) { 1_000L }),
                TranscriptSync(transcripts, cursors, clock = { 1_000L }),
                OutboxDrain(outbox) { 1_000L },
            )
    }

    private fun summary(id: String, updatedAt: Long = 100) =
        SessionSummary(
            sessionId = id,
            title = "Title $id",
            cwd = null,
            running = false,
            blank = false,
            agentAvailable = true,
            parentSessionId = null,
            origin = null,
            updatedAt = updatedAt,
        )

    private fun record(seq: Long) =
        SessionRecord(seq, seq * 10, "user/message", buildJsonObject { put("text", "m$seq") })

    @Test
    fun syncDirectoryPersistsAndTheWatcherReadsItBack() = runTest {
        val fixture = Fixture()

        val report = fixture.mirror.syncDirectory(host) { listOf(summary("a"), summary("b", 200)) }

        assertEquals(SyncReport(written = 2, total = 2), report)
        assertEquals(
            listOf("b", "a"),
            fixture.mirror.watch(host).first().map { it.sessionId },
        )
        assertEquals(listOf("b", "a"), fixture.mirror.cached(host).map { it.sessionId })
    }

    @Test
    fun mirroredRecordsReachTheLocalTranscriptCache() = runTest {
        val fixture = Fixture()

        assertEquals(2, fixture.mirror.syncTranscript(host, "s", listOf(record(1), record(2))))

        assertEquals(
            listOf(1L, 2L),
            fixture.mirror.cachedTranscript(host, "s", limit = 10).map { it.seq },
        )
    }

    @Test
    fun queuedWritesDrainThroughTheMirror() = runTest {
        val fixture = Fixture()
        fixture.mirror.enqueueRename(host, "s", "First title", "r1")
        fixture.mirror.enqueueRename(host, "s", "Second title", "r2")

        val report = fixture.mirror.drainWrites(host) { Delivery.Acknowledged }

        assertEquals(
            DrainReport(
                acknowledged = 2,
                rejected = 0,
                unknown = 0,
                settledSessions = listOf("s", "s"),
            ),
            report,
        )
        assertEquals(0, fixture.outbox.count(host))
    }

    @Test
    fun removingAHostDropsItsMirrorAndQueuedWritesButLeavesOtherHosts() = runTest {
        val fixture = Fixture()
        fixture.mirror.syncDirectory(host) { listOf(summary("a")) }
        fixture.mirror.syncTranscript(host, "a", listOf(record(1)))
        fixture.mirror.enqueueWrite(host, "a", SessionMirror.KIND_RENAME, "{}", "r1")
        fixture.mirror.syncDirectory("other") { listOf(summary("b")) }
        fixture.mirror.syncTranscript("other", "b", listOf(record(2)))

        fixture.mirror.clearHost(host)

        assertTrue(fixture.mirror.cached(host).isEmpty())
        assertTrue(fixture.transcripts.storage.keys.none { it.startsWith("$host/") })
        assertTrue(fixture.cursors.storage.keys.none { it.startsWith("$host/") })
        assertTrue(fixture.outbox.storage.none { it.hostId == host })
        assertEquals(listOf("b"), fixture.mirror.cached("other").map { it.sessionId })
        assertEquals(1, fixture.transcripts.storage.count { it.key.startsWith("other/") })
    }

    @Test
    fun aSessionDeletedOnTheHostLeavesTheOfflineListWithItsTranscript() = runTest {
        val fixture = Fixture()
        fixture.mirror.syncDirectory(host) { listOf(summary("a"), summary("b", 200)) }
        fixture.mirror.syncTranscript(host, "b", listOf(record(1)))
        assertEquals(1, fixture.transcripts.storage.size)

        val report = fixture.mirror.syncDirectory(host) { listOf(summary("a")) }

        assertEquals(listOf("b"), report.removed)
        assertEquals(listOf("a"), fixture.mirror.cached(host).map { it.sessionId })
        assertTrue(fixture.transcripts.storage.keys.none { it.startsWith("$host/b/") })
        // The deleted session's paging cursor goes with it.
        assertTrue(fixture.cursors.storage.keys.none { it.endsWith("/session:b") })
    }
}

package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.SessionRow
import dev.fughetta.dshcompose.data.database.SyncStates
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionDirectorySyncTest {
    private val host = "host"

    private fun summary(
        id: String,
        title: String? = "Title $id",
        updatedAt: Long = 100,
        running: Boolean = false,
    ) =
        SessionSummary(
            sessionId = id,
            title = title,
            cwd = null,
            running = running,
            blank = false,
            agentAvailable = true,
            parentSessionId = null,
            origin = null,
            updatedAt = updatedAt,
        )

    @Test
    fun firstSyncWritesEveryRowAndStoresTheCursor() = runTest {
        val sessions = FakeSessionMirror()
        val cursors = FakeCursors()
        var now = 1_000L
        val sync = SessionDirectorySync(sessions, cursors) { now }

        val report = sync.sync(host) { listOf(summary("a"), summary("b", running = true)) }

        assertEquals(SyncReport(written = 2, total = 2), report)
        assertEquals(2, sessions.storage.size)
        assertEquals(true, sessions.storage.getValue("$host/b").running)
        assertEquals("1000", cursors.storage["$host/$DIRECTORY_STREAM"]?.cursor)
    }

    @Test
    fun unchangedDirectoryWritesNothingButRefreshesTheCursor() = runTest {
        val sessions = FakeSessionMirror()
        val cursors = FakeCursors()
        var now = 1_000L
        val sync = SessionDirectorySync(sessions, cursors) { now }
        val remote = listOf(summary("a"), summary("b"))
        sync.sync(host) { remote }
        val writesAfterFirst = sessions.writes

        now = 2_000L
        val report = sync.sync(host) { remote }

        assertEquals(SyncReport(written = 0, total = 2), report)
        assertEquals(writesAfterFirst, sessions.writes)
        assertEquals("2000", cursors.storage["$host/$DIRECTORY_STREAM"]?.cursor)
    }

    @Test
    fun changedEngineFieldsAreWrittenWhileTheLocalSyncStateIsKept() = runTest {
        val sessions = FakeSessionMirror()
        val cursors = FakeCursors()
        val sync = SessionDirectorySync(sessions, cursors) { 1_000L }
        sessions.storage["$host/a"] =
            SessionRow(host, "a", "Old", 100, false, "rev-1", SyncStates.PENDING)
        sessions.writes = 0

        val report = sync.sync(host) { listOf(summary("a", title = "Renamed", updatedAt = 200)) }

        assertEquals(SyncReport(written = 1, total = 1), report)
        val row = sessions.storage.getValue("$host/a")
        assertEquals("Renamed", row.title)
        assertEquals(200L, row.updatedAt)
        assertEquals("rev-1", row.remoteRev)
        assertEquals(SyncStates.PENDING, row.syncState)
    }

    @Test
    fun anotherHostIsLeftAlone() = runTest {
        val sessions = FakeSessionMirror()
        val cursors = FakeCursors()
        val sync = SessionDirectorySync(sessions, cursors) { 1_000L }
        sessions.storage["other/a"] =
            SessionRow("other", "a", "Kept", 100, false, null, SyncStates.ACKED)

        sync.sync(host) { listOf(summary("a")) }

        assertEquals("Kept", sessions.storage.getValue("other/a").title)
    }
}

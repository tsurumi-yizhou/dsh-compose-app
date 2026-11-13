package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.SessionRow
import dev.fughetta.dshcompose.data.database.SyncStates
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalSessionCatalogTest {
    private val host = "host"

    private fun summary(id: String, title: String? = "Title $id", updatedAt: Long = 100) =
        SessionSummary(
            sessionId = id,
            title = title,
            cwd = null,
            running = false,
            blank = false,
            agentAvailable = true,
            parentSessionId = null,
            origin = null,
            updatedAt = updatedAt,
        )

    private fun catalog(sessions: FakeSessionMirror, cursors: FakeCursors = FakeCursors()) =
        LocalSessionCatalog(sessions, SessionDirectorySync(sessions, cursors) { 1_000L })

    @Test
    fun refreshWritesThroughTheMirrorAndWatchersObserveTheLocalRows() = runTest {
        val sessions = FakeSessionMirror()
        val catalog = catalog(sessions)

        val report =
            catalog.refresh(host) {
                listOf(summary("a", updatedAt = 10), summary("b", updatedAt = 20))
            }

        assertEquals(SyncReport(written = 2, total = 2), report)
        assertEquals(
            listOf("b", "a"),
            catalog.watch(host).first().map { it.sessionId },
        )
    }

    @Test
    fun cachedReadsTheMirrorWithoutTouchingTheEngine() = runTest {
        val sessions = FakeSessionMirror()
        sessions.storage["$host/a"] =
            SessionRow(host, "a", "Kept", 10, false, null, SyncStates.ACKED)
        val catalog = catalog(sessions)

        val cached = catalog.cached(host)

        assertEquals(listOf("a"), cached.map { it.sessionId })
        assertEquals("Kept", cached.single().title)
    }

    @Test
    fun anEmptyTitleMapsToNoTitle() = runTest {
        val sessions = FakeSessionMirror()
        sessions.storage["$host/a"] = SessionRow(host, "a", "", 10, false, null, SyncStates.ACKED)

        assertNull(catalog(sessions).cached(host).single().title)
    }

    @Test
    fun anotherHostsRowsNeverReachThisHostsReaders() = runTest {
        val sessions = FakeSessionMirror()
        val catalog = catalog(sessions)
        catalog.refresh(host) { listOf(summary("a")) }

        assertEquals(0, catalog.watch("other").first().size)
    }

    @Test
    fun markSyncedClearsThePendingMarkerOnceTheHostAnswers() = runTest {
        val sessions = FakeSessionMirror()
        sessions.storage["host/a"] =
            SessionRow(
                hostId = "host",
                sessionId = "a",
                title = "Local title",
                updatedAt = 5,
                running = false,
                remoteRev = null,
                syncState = SyncStates.PENDING,
            )
        val catalog = catalog(sessions)
        assertTrue(catalog.cached(host).single().pendingWrite)

        catalog.markSynced(host, "a")

        assertFalse(catalog.cached(host).single().pendingWrite)
    }
}

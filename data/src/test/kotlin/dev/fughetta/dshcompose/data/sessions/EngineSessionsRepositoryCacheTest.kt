package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.database.OutboxRow
import dev.fughetta.dshcompose.data.sync.Delivery
import dev.fughetta.dshcompose.data.sync.DrainReport
import dev.fughetta.dshcompose.data.sync.SessionCache
import dev.fughetta.dshcompose.data.sync.SessionMirror
import dev.fughetta.dshcompose.data.sync.SyncReport
import dev.fughetta.dshcompose.data.testing.SessionHost
import dev.fughetta.dshcompose.data.testing.connect
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.SessionForkState
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.protocol.ConnectionClosedException
import dev.fughetta.dshcompose.protocol.HostRemoteException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineSessionsRepositoryCacheTest {
    private class Source(
        private val rows: List<SessionSummary>,
        private val renameFailure: Exception? = null,
    ) : SessionHost() {
        val renamed = mutableListOf<Pair<String, String>>()

        override suspend fun list() = rows

        override suspend fun create(cwd: String?, workspaceId: String?, agentPreset: String?) =
            "created"

        override suspend fun rename(id: String, title: String): String {
            renameFailure?.let { throw it }
            renamed += id to title
            return title
        }

        override suspend fun forkSession(id: String, atSeq: Long?) = id

        override suspend fun forkState(id: String): SessionForkState? = null

        override suspend fun updateQueuedPrompt(id: String, itemId: String, change: QueueChange) =
            Unit

        override suspend fun createdAt(id: String): Long? = null

        override suspend fun delete(id: String) = Unit
    }

    private abstract class ReadCache : SessionCache {
        override suspend fun outstandingRenameSessions(hostId: String): Set<String> = emptySet()

        override suspend fun retryUnknown(hostId: String): Int = 0

        override suspend fun drainWrites(hostId: String, deliver: suspend (OutboxRow) -> Delivery) =
            DrainReport(0, 0, 0, emptyList())

        override suspend fun renameLocal(
            hostId: String,
            sessionId: String,
            title: String,
            at: Long,
        ) {
            error("Unexpected rename")
        }

        override suspend fun enqueueRename(
            hostId: String,
            sessionId: String,
            title: String,
            receiptId: String,
        ) {
            error("Unexpected queued rename")
        }

        override suspend fun markSynced(hostId: String, sessionId: String) {
            error("Unexpected sync marker")
        }
    }

    private class Cache(
        private val rows: List<SessionSummary>,
        private val failStore: Boolean = false,
    ) : ReadCache() {
        var stored: List<SessionSummary>? = null

        override fun watch(hostId: String): Flow<List<SessionSummary>> = flowOf(rows)

        override suspend fun cached(hostId: String) = rows

        override suspend fun store(hostId: String, rows: List<SessionSummary>): SyncReport {
            if (failStore) throw IllegalStateException("mirror-unavailable")
            stored = rows
            return SyncReport(written = rows.size, total = rows.size)
        }
    }

    private class WriteCache(
        private val rows: List<SessionSummary>,
        val queued: MutableList<Triple<String, String, String>> = mutableListOf(),
    ) : SessionCache {
        val renamed = mutableListOf<Triple<String, String, String>>()
        val synced = mutableListOf<String>()
        var outstandingRenames: Set<String> = emptySet()
        val deliveries = mutableListOf<Delivery>()
        var drained = 0
        var retryUnknownCalls = 0

        override fun watch(hostId: String): Flow<List<SessionSummary>> = flowOf(rows)

        override suspend fun cached(hostId: String) = rows

        override suspend fun store(hostId: String, rows: List<SessionSummary>): SyncReport =
            SyncReport(written = rows.size, total = rows.size)

        override suspend fun outstandingRenameSessions(hostId: String): Set<String> =
            outstandingRenames

        override suspend fun markSynced(hostId: String, sessionId: String) {
            synced += sessionId
        }

        override suspend fun renameLocal(
            hostId: String,
            sessionId: String,
            title: String,
            at: Long,
        ) {
            renamed += Triple(sessionId, title, hostId)
        }

        override suspend fun enqueueRename(
            hostId: String,
            sessionId: String,
            title: String,
            receiptId: String,
        ) {
            queued += Triple(sessionId, title, hostId)
        }

        override suspend fun retryUnknown(hostId: String): Int {
            retryUnknownCalls++
            return 0
        }

        override suspend fun drainWrites(
            hostId: String,
            deliver: suspend (OutboxRow) -> Delivery,
        ): DrainReport {
            var acknowledged = 0
            val settled = mutableListOf<String>()
            for ((sessionId, title, _) in queued.toList()) {
                val payload =
                    kotlinx.serialization.json.buildJsonObject {
                        put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId))
                        put("title", kotlinx.serialization.json.JsonPrimitive(title))
                    }
                val row =
                    OutboxRow(
                        hostId = hostId,
                        sessionId = sessionId,
                        kind = SessionMirror.KIND_RENAME,
                        payload = payload.toString(),
                        receiptId = "receipt-$sessionId",
                        state = "pending",
                        createdAt = 0,
                    )
                val answer = deliver(row)
                deliveries += answer
                when (answer) {
                    is Delivery.Acknowledged -> {
                        acknowledged++
                        settled += sessionId
                    }
                    is Delivery.Rejected -> settled += sessionId
                    Delivery.Unknown -> {}
                }
            }
            queued.clear()
            drained += acknowledged
            return DrainReport(
                acknowledged,
                deliveries.count { it is Delivery.Rejected },
                deliveries.count { it == Delivery.Unknown },
                settled,
            )
        }
    }

    private fun summary(
        id: String,
        origin: String? = null,
        updatedAt: Long = 100,
        pendingWrite: Boolean = false,
    ) =
        SessionSummary(
            sessionId = id,
            title = "Title $id",
            cwd = null,
            running = false,
            blank = false,
            agentAvailable = true,
            parentSessionId = null,
            origin = origin,
            updatedAt = updatedAt,
            pendingWrite = pendingWrite,
        )

    @Test
    fun offlineCatalogFollowsLaterMirrorWrites() = runTest {
        val updates = MutableStateFlow(listOf(summary("a")))
        val cache =
            object : ReadCache() {
                override fun watch(hostId: String): Flow<List<SessionSummary>> = updates

                override suspend fun cached(hostId: String) = updates.value

                override suspend fun store(
                    hostId: String,
                    rows: List<SessionSummary>,
                ): SyncReport = SyncReport(written = 0, total = rows.size)
            }
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()
        assertEquals(listOf("a"), repository.state.value.sessions.map { it.sessionId })

        updates.value = listOf(summary("b", updatedAt = 200), summary("a", updatedAt = 10))
        runCurrent()

        assertEquals(listOf("b", "a"), repository.state.value.sessions.map { it.sessionId })
        updates.value = emptyList()
        runCurrent()
        assertTrue(repository.state.value.sessions.isEmpty())
        assertTrue(repository.state.value.allSessions.isEmpty())
        repository.close()
    }

    @Test
    fun offlineRenameShowsTheNewTitleAndQueuesTheWrite() = runTest {
        val cache = WriteCache(listOf(summary("a")))
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()

        val title = repository.renameOffline("a", "  Renamed offline  ")

        assertEquals("Renamed offline", title)
        assertEquals(
            Triple("a", "Renamed offline", "host"),
            cache.renamed.single(),
        )
        assertEquals(Triple("a", "Renamed offline", "host"), cache.queued.single())
        assertEquals("Renamed offline", repository.state.value.sessions.single().title)
        repository.close()
    }

    @Test
    fun offlineRenameIsRejectedWhileConnected() = runTest {
        val cache = WriteCache(listOf(summary("a")))
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")

        val error = runCatching { repository.renameOffline("a", "later") }.exceptionOrNull()

        assertTrue(error is ConnectionClosedException)
        assertTrue(cache.queued.isEmpty())
        repository.close()
    }

    @Test
    fun connectingRequeuesWritesAnEarlierConnectionLeftUnknown() = runTest {
        val cache = WriteCache(listOf(summary("a")))
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()
        repository.renameOffline("a", "Queued")

        repository.connect(Source(listOf(summary("a"))), backgroundScope, "host")
        runCurrent()

        assertEquals(1, cache.retryUnknownCalls)
        repository.close()
    }

    @Test
    fun anEngineRejectionIsNeverRetriedAsUnknown() = runTest {
        val cache = WriteCache(listOf(summary("a")))
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()
        repository.renameOffline("a", "Denied")

        repository.connect(
            Source(
                listOf(summary("a")),
                renameFailure = HostRemoteException("session/rename-denied"),
            ),
            backgroundScope,
            "host",
        )
        runCurrent()

        assertEquals(
            listOf(Delivery.Rejected("session/rename-denied")),
            cache.deliveries,
        )
        assertEquals(0, cache.drained)
        // A rejection is a final answer: the row stops waiting even though nothing is retried.
        assertEquals(listOf("a"), cache.synced)
        repository.close()
    }

    @Test
    fun aTransportFailureStaysUnknownForTheNextConnection() = runTest {
        val cache = WriteCache(listOf(summary("a")))
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()
        repository.renameOffline("a", "Flaky")

        repository.connect(
            Source(listOf(summary("a")), renameFailure = IllegalStateException("socket")),
            backgroundScope,
            "host",
        )
        runCurrent()

        assertEquals(listOf<Delivery>(Delivery.Unknown), cache.deliveries)
        // No answer arrived, so the mirrored row keeps its "waiting to sync" marker.
        assertTrue(cache.synced.isEmpty())
        repository.close()
    }

    @Test
    fun queuedRenamesReplayWhenTheHostConnects() = runTest {
        val cache = WriteCache(listOf(summary("a")))
        val repository = EngineSessionsRepository(cache, backgroundScope)
        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()
        repository.renameOffline("a", "Queued title")

        val source = Source(listOf(summary("a")))
        repository.connect(source, backgroundScope, "host")
        runCurrent()

        assertEquals(listOf("a" to "Queued title"), source.renamed)
        assertEquals(1, cache.drained)
        assertEquals(listOf("a"), cache.synced)
        repository.close()
    }

    @Test
    fun offlineStartPublishesTheMirroredDirectory() = runTest {
        val cached = listOf(summary("a", updatedAt = 10), summary("sub", origin = "subagent"))
        val repository = EngineSessionsRepository(Cache(cached), backgroundScope)

        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()

        val state = repository.state.value
        assertEquals(listOf("a"), state.sessions.map { it.sessionId })
        assertEquals(setOf("a", "sub"), state.allSessions.map { it.sessionId }.toSet())
        assertFalse(state.available)
        assertTrue(state.initialized)
    }

    @Test
    fun aLiveConnectionStillPublishesTheEngineRows() = runTest {
        val cache = Cache(listOf(summary("cached")))
        val repository = EngineSessionsRepository(cache, backgroundScope)

        repository.connect(Source(listOf(summary("live"))), backgroundScope, "host")
        repository.refresh()

        val state = repository.state.value
        assertEquals(listOf("live"), state.sessions.map { it.sessionId })
        assertTrue(state.available)
        assertEquals(listOf("live"), cache.stored?.map { it.sessionId })
    }

    @Test
    fun aFailingMirrorDoesNotFailTheRefresh() = runTest {
        val repository =
            EngineSessionsRepository(
                Cache(listOf(summary("cached")), failStore = true),
                backgroundScope,
            )

        repository.connect(Source(listOf(summary("live"))), backgroundScope, "host")
        repository.refresh()

        assertEquals(listOf("live"), repository.state.value.sessions.map { it.sessionId })
    }

    @Test
    fun anEmptyMirrorLeavesTheCatalogEmpty() = runTest {
        val repository = EngineSessionsRepository(Cache(emptyList()), backgroundScope)

        repository.connect(Source(emptyList()), backgroundScope, "host")
        repository.disconnect()
        runCurrent()

        assertTrue(repository.state.value.sessions.isEmpty())
        assertTrue(repository.state.value.allSessions.isEmpty())
    }

    @Test
    fun aPendingMarkerLeftByADeadProcessIsClearedOnTheNextConnection() = runTest {
        val cache = WriteCache(listOf(summary("a", pendingWrite = true)))
        val repository = EngineSessionsRepository(cache, backgroundScope)

        // A connection's refresh leaves the marker behind; the follow-up reconcile clears it.
        repository.reconcilePendingWrites("host")

        assertEquals(listOf("a"), cache.synced)
        repository.close()
    }

    @Test
    fun aStillQueuedRenameKeepsItsPendingMarker() = runTest {
        val cache = WriteCache(listOf(summary("a", pendingWrite = true)))
        cache.outstandingRenames = setOf("a")
        val repository = EngineSessionsRepository(cache, backgroundScope)

        repository.reconcilePendingWrites("host")

        assertTrue(cache.synced.isEmpty())
        repository.close()
    }
}

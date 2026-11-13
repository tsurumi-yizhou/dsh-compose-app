package dev.fughetta.dshcompose.data.sync

import android.app.Application
import androidx.room.Room
import dev.fughetta.dshcompose.data.database.AppDatabase
import dev.fughetta.dshcompose.data.database.SyncStates
import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository
import dev.fughetta.dshcompose.data.sessions.TranscriptClock
import dev.fughetta.dshcompose.data.sessions.cachedTranscriptRepository
import dev.fughetta.dshcompose.data.testing.SessionHost
import dev.fughetta.dshcompose.data.testing.connect
import dev.fughetta.dshcompose.domain.messages.PendingPrompt
import dev.fughetta.dshcompose.domain.messages.PendingPromptState
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.SessionForkState
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.domain.sessions.TranscriptSnapshot
import dev.fughetta.dshcompose.protocol.SessionRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode

/**
 * End-to-end offline flow over the real Room schema: mirror writes, the disconnected catalog
 * fallback, a cached transcript and a durable pending prompt. The other sync tests use fake DAOs;
 * this one proves the JSON payloads and schema survive a real database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OfflineFlowTest {
    private val host = "host"
    private lateinit var database: AppDatabase
    private lateinit var mirror: SessionMirror

    @Before
    fun open() {
        database =
            Room.inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    AppDatabase::class.java,
                )
                .build()
        mirror =
            SessionMirror(
                LocalSessionCatalog(
                    database.sessionMirror(),
                    SessionDirectorySync(database.sessionMirror(), database.syncCursors()),
                ),
                TranscriptSync(database.transcriptMirror(), database.syncCursors()),
                OutboxDrain(database.outbox()),
            )
    }

    @After
    fun close() {
        database.close()
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

    private fun record(seq: Long, text: String) =
        SessionRecord(
            seq,
            seq,
            "user/message",
            buildJsonObject {
                putJsonObject("source") { put("kind", "user") }
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            },
        )

    private class RenamingSource(
        private val id: String,
        var title: String,
        private val updatedAt: Long = 100,
        private val reject: Boolean = false,
    ) : SessionHost() {
        val renamed = mutableListOf<Pair<String, String>>()

        override suspend fun list() =
            listOf(
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
                ),
            )

        override suspend fun create(cwd: String?, workspaceId: String?, agentPreset: String?) =
            "created"

        override suspend fun rename(id: String, title: String): String {
            renamed += id to title
            if (reject)
                throw dev.fughetta.dshcompose.protocol.HostRemoteException(
                    "session/rename-rejected",
                )
            this.title = title
            return title
        }

        override suspend fun forkSession(id: String, atSeq: Long?) = id

        override suspend fun forkState(id: String): SessionForkState? = null

        override suspend fun updateQueuedPrompt(id: String, itemId: String, change: QueueChange) =
            Unit

        override suspend fun createdAt(id: String): Long? = null

        override suspend fun delete(id: String) = Unit
    }

    private class Source : SessionHost() {
        override suspend fun list() = emptyList<SessionSummary>()

        override suspend fun create(cwd: String?, workspaceId: String?, agentPreset: String?) =
            "created"

        override suspend fun rename(id: String, title: String) = title

        override suspend fun forkSession(id: String, atSeq: Long?) = id

        override suspend fun forkState(id: String): SessionForkState? = null

        override suspend fun updateQueuedPrompt(id: String, itemId: String, change: QueueChange) =
            Unit

        override suspend fun createdAt(id: String): Long? = null

        override suspend fun delete(id: String) = Unit
    }

    @Test
    fun theMirroredSessionListSurvivesADisconnectedCatalog() = runBlocking {
        withTimeout(15_000) {
            mirror.syncDirectory(host) { listOf(summary("a"), summary("b", 200)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineSessionsRepository(mirror, scope)
            try {
                repository.connect(Source(), scope, host)
                repository.disconnect()

                val state =
                    withTimeout(10_000) { repository.state.first { it.sessions.isNotEmpty() } }
                assertEquals(listOf("b", "a"), state.sessions.map { it.sessionId })
                assertFalse(state.available)
                assertTrue(state.initialized)
            } finally {
                repository.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun theMirroredTranscriptFoldsIntoAReadOnlyConversation() = runBlocking {
        withTimeout(15_000) {
            mirror.syncTranscript(host, "session", listOf(record(1, "offline hello")))
            val records = mirror.cachedRecords(host, "session", limit = 10)
            assertEquals(1, records.size)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val pending = EngineMessagesRepository(scope)
            val catalog = EngineSessionsRepository()
            val transcript =
                cachedTranscriptRepository(
                    host = host,
                    sessionId = "session",
                    home = "/home",
                    records = records,
                    parent = scope,
                    pendingMessages = pending,
                    catalog = catalog,
                    clock = TranscriptClock(),
                )
            try {
                val publication =
                    withTimeout(10_000) { transcript.updates { _, _ -> }.first { !it.syncing } }
                val users = publication.presentation.items.map { it.text }
                assertEquals(listOf("offline hello"), users)
            } finally {
                transcript.close()
                pending.close()
                catalog.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun olderMirroredPagesLoadOnScrollBack() = runBlocking {
        withTimeout(20_000) {
            val records = (1L..250L).map { record(it, "m$it") }
            mirror.syncTranscript(host, "session", records)
            val page = mirror.cachedRecords(host, "session", limit = 200)
            assertEquals(200, page.size)
            assertEquals(51L, page.first().seq)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val pending = EngineMessagesRepository(scope)
            val catalog = EngineSessionsRepository()
            val transcript =
                cachedTranscriptRepository(
                    host = host,
                    sessionId = "session",
                    home = "/home",
                    records = page,
                    parent = scope,
                    pendingMessages = pending,
                    catalog = catalog,
                    clock = TranscriptClock(),
                    hasMore = true,
                    older = { beforeSeq, limit ->
                        mirror.cachedRecordsBefore(host, "session", beforeSeq, limit)
                    },
                )
            val publications = Channel<TranscriptSnapshot>(64)
            val reader = scope.launch {
                transcript.updates { _, _ -> }.collect { publications.send(it) }
            }
            try {
                val first =
                    withTimeout(10_000) {
                        var current = publications.receive()
                        while (current.syncing) current = publications.receive()
                        current
                    }
                assertFalse(first.presentation.items.any { it.text == "m1" })
                assertTrue(first.hasMoreHistory)

                transcript.loadHistory()

                val second =
                    withTimeout(10_000) {
                        var current = publications.receive()
                        while (!current.presentation.items.any { it.text == "m1" }) current =
                            publications.receive()
                        current
                    }
                assertTrue(second.presentation.items.any { it.text == "m1" })
            } finally {
                reader.cancel()
                transcript.close()
                pending.close()
                catalog.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun aLocalRenameReachesTheRealRowsAndTheOutbox() = runTest {
        mirror.syncDirectory(host) { listOf(summary("a")) }
        assertEquals("Title a", mirror.cached(host).single().title)

        mirror.renameLocal(host, "a", "Renamed offline", at = 200)
        mirror.enqueueRename(host, "a", "Renamed offline", "receipt-1")

        assertEquals("Renamed offline", mirror.cached(host).single().title)
        assertTrue(mirror.cached(host).single().pendingWrite)
        val report =
            mirror.drainWrites(host) { dev.fughetta.dshcompose.data.sync.Delivery.Acknowledged }
        assertEquals(1, report.acknowledged)
        assertEquals("Renamed offline", mirror.cached(host).single().title)
        assertTrue(mirror.cached(host).single().pendingWrite)
    }

    @Test
    fun aPendingPromptSurvivesCatalogReconnectAndRenameReplay() = runBlocking {
        val prompts = PendingPromptOutbox(database.outbox()) { 1_000L }
        val prompt =
            PendingPrompt(
                requestId = "request-1",
                host = host,
                home = "/home",
                sessionId = "session",
                text = "unsent",
                quote = null,
                composerText = "unsent",
                files = kotlinx.collections.immutable.persistentListOf(),
                state = PendingPromptState.Uncertain,
                attempt = 1,
            )
        prompts.retain(prompt)

        mirror.syncDirectory(host) { listOf(summary("session")) }
        mirror.renameLocal(host, "session", "Renamed offline", at = 200)
        mirror.enqueueRename(host, "session", "Renamed offline", "rename-receipt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = RenamingSource("session", "Title session")
        val repository = EngineSessionsRepository(mirror, scope)
        try {
            repository.connect(source, scope, host)
            withTimeout(10_000) { while (mirror.cached(host).single().pendingWrite) delay(20) }
            assertEquals(listOf("session" to "Renamed offline"), source.renamed)
            val restored = PendingPromptOutbox(database.outbox()).outstanding(host).single()
            assertEquals("request-1", restored.requestId)
            assertEquals("unsent", restored.text)
            assertEquals(PendingPromptState.Uncertain, restored.state)
            val row = database.outbox().byKind(host, "prompt").single()
            assertEquals(SyncStates.UNKNOWN, row.state)
            assertEquals(prompt.attempt.toInt(), row.attempts)
        } finally {
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun anOfflineRenameReplaysAndDropsItsPendingMarkerAfterReconnecting() = runBlocking {
        withTimeout(20_000) {
            mirror.syncDirectory(host) { listOf(summary("a")) }
            mirror.renameLocal(host, "a", "Renamed offline", at = 200)
            mirror.enqueueRename(host, "a", "Renamed offline", "receipt-offline")
            assertTrue(mirror.cached(host).single().pendingWrite)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val source = RenamingSource("a", "Title a")
            val repository = EngineSessionsRepository(mirror, scope)
            try {
                repository.connect(source, scope, host)
                withTimeout(10_000) { while (mirror.cached(host).single().pendingWrite) delay(20) }

                assertEquals(listOf("a" to "Renamed offline"), source.renamed)
                assertEquals("Renamed offline", mirror.cached(host).single().title)
            } finally {
                repository.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun aRenameTheHostRejectsDropsTheMarkerAndKeepsTheHostTitle() = runBlocking {
        withTimeout(20_000) {
            mirror.syncDirectory(host) { listOf(summary("a")) }
            mirror.renameLocal(host, "a", "Rejected rename", at = 200)
            mirror.enqueueRename(host, "a", "Rejected rename", "receipt-rejected")

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val source = RenamingSource("a", "Title a", reject = true)
            val repository = EngineSessionsRepository(mirror, scope)
            try {
                repository.connect(source, scope, host)
                // The marker drops when the rejection is drained; the host title returns with the
                // refresh that follows. Wait for the settled state rather than either step alone.
                withTimeout(10_000) {
                    while (
                        mirror.cached(host).single().let {
                            it.pendingWrite || it.title != "Title a"
                        }
                    ) {
                        delay(20)
                    }
                }

                // A rejection is final: one attempt, no marker, host title wins.
                assertEquals(listOf("a" to "Rejected rename"), source.renamed)
                assertEquals("Title a", mirror.cached(host).single().title)
            } finally {
                repository.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun removingAHostEmptiesItsOfflineCatalogAndHistory() = runBlocking {
        withTimeout(20_000) {
            mirror.syncDirectory(host) { listOf(summary("a"), summary("b", 200)) }
            mirror.syncTranscript(host, "a", listOf(record(1, "hello")))

            mirror.clearHost(host)

            assertTrue(mirror.cached(host).isEmpty())
            assertTrue(mirror.cachedRecords(host, "a", limit = 10).isEmpty())
            assertTrue(mirror.watch(host).first().isEmpty())
        }
    }
}

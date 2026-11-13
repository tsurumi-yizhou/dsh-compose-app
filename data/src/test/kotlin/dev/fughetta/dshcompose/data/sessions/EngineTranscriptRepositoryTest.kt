package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.sync.TranscriptMirrorSink
import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.data.testing.SessionHost
import dev.fughetta.dshcompose.data.testing.connect
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.PendingPromptsOwner
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.ConversationRepository
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.domain.sessions.TranscriptSnapshot
import dev.fughetta.dshcompose.protocol.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineTranscriptRepositoryTest {
    private class Source : AutoCloseable {
        private val follows = Channel<suspend (JsonObject) -> Unit>(Channel.UNLIMITED)
        private lateinit var send: suspend (JsonObject) -> Unit
        val requested = Channel<Pair<Long, Long?>>(Channel.UNLIMITED)
        val response = CompletableDeferred<HistoryPage>()
        val cancelled = CompletableDeferred<Unit>()
        private val host =
            ProtocolHost(
                home = "/home",
                openStream = { endpoint, sink ->
                    check(endpoint == "session/follow")
                    follows.trySend(sink).getOrThrow()
                    Unit
                },
            ) { endpoint, args ->
                when (endpoint) {
                    "session/page" -> {
                        val request = args.getValue("request").jsonObject
                        requested.send(
                            request.getValue("throughSeq").jsonPrimitive.long to
                                request["beforeSeq"]?.jsonPrimitive?.long,
                        )
                        val page =
                            try {
                                response.await()
                            } finally {
                                cancelled.complete(Unit)
                            }
                        buildJsonObject {
                            put("records", records(page.records))
                            put("hasMore", page.hasMore)
                        }
                    }
                    "session/list" ->
                        Json.parseToJsonElement(
                            """{"items":[{"sessionId":"session","projections":{"values":{"title":"Listed"}},"agentAvailable":true}]}""",
                        )
                    else -> error("Unexpected request: $endpoint")
                }
            }
        val client
            get() = host.client

        private fun record(value: SessionRecord) = buildJsonObject {
            put("seq", value.seq)
            put("time", value.time)
            put("type", value.type)
            put("data", value.data)
        }

        private fun records(values: List<SessionRecord>) = buildJsonArray {
            values.forEach { value -> addJsonObject { put("event", record(value)) } }
        }

        suspend fun emit(update: SessionUpdate) {
            send(
                when (update) {
                    is SessionUpdate.Snapshot ->
                        buildJsonObject {
                            put("type", "snapshot")
                            put("cursor", update.cursor)
                            put("records", records(update.records))
                            put("hasMore", update.hasMore)
                            put("header", update.header)
                            put("projections", update.projections)
                        }
                    is SessionUpdate.Record ->
                        buildJsonObject {
                            put("type", "event")
                            put("event", record(update.event))
                        }
                    else -> error("Unexpected test update")
                },
            )
        }

        suspend fun reset(number: Long): ConnectionGeneration {
            if (number > 1) client.mux.reconnect()
            val lease = client.mux.ready()
            send = follows.receive()
            check(lease.number == number)
            return lease
        }

        override fun close() = host.close()
    }

    private class Fixture(
        private val mirror: TranscriptMirrorSink? = null,
        selected: (() -> Boolean)? = null,
    ) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val pending = EngineMessagesRepository(scope)
        val catalog = EngineSessionsRepository()
        val repository =
            EngineTranscriptRepository(
                source.client,
                "host",
                "session",
                scope,
                pending,
                catalog,
                TranscriptClock(),
                null,
                mirror,
            )
        val publications = Channel<TranscriptSnapshot>(Channel.UNLIMITED)
        val observed = Channel<SessionUpdate>(Channel.UNLIMITED)
        var pause: CompletableDeferred<Unit>? = null
        val reader = scope.launch {
            val updates =
                if (selected == null) repository.updates { event, _ -> observed.send(event) }
                else repository.publications(isCurrentConnection = selected)
            updates.collect {
                publications.send(it)
                if (!it.syncing) pause?.await()
            }
        }

        suspend fun publication(predicate: (TranscriptSnapshot) -> Boolean): TranscriptSnapshot {
            while (true) {
                val value = publications.receive()
                if (predicate(value)) return value
            }
        }

        override fun close() {
            repository.close()
            pending.close()
            catalog.close()
            scope.cancel()
            source.close()
        }
    }

    @Test
    fun selectedTranscriptFollowsReconnectAndRejectsPublicationsAfterHostSwitch() = runBlocking {
        withTimeout(5000) {
            val selected = AtomicBoolean(true)
            val rejected = CompletableDeferred<Unit>()
            Fixture(selected = { selected.get().also { if (!it) rejected.complete(Unit) } }).use {
                fixture ->
                val first = fixture.source.reset(1)
                fixture.source.emit(snapshot(first, 1, "Current"))
                val current = fixture.publication { !it.syncing }
                assertEquals(
                    listOf("Current"),
                    current.presentation.items.map { it.text },
                )

                val next = fixture.source.reset(2)
                fixture.source.emit(snapshot(next, 1, "Another generation"))
                val reconnected = fixture.publication { it.generation == next && !it.syncing }
                assertTrue(reconnected.historyEpoch > current.historyEpoch)
                assertEquals(
                    listOf("Another generation"),
                    reconnected.presentation.items.map { it.text },
                )

                selected.set(false)
                fixture.source.emit(SessionUpdate.Record(record(2, "Retired host")))
                rejected.await()
                fixture.repository.close()
                fixture.reader.join()

                fixture.publications.close()
                val remaining = fixture.publications.receiveAsFlow().toList()
                assertTrue(
                    remaining.none { publication ->
                        publication.presentation.items.any { it.text == "Retired host" }
                    },
                )
            }
        }
    }

    @Test
    fun mirroredRecordsReachTheSinkInOrderEvenWhenCoalesced() = runBlocking {
        withTimeout(15_000) {
            val batches = Channel<List<SessionRecord>>(Channel.UNLIMITED)
            val fixture = Fixture(mirror = { _, _, records -> batches.send(records) })
            try {
                val lease = fixture.source.reset(1)
                fixture.source.emit(snapshot(lease, 1, "a"))
                fixture.source.emit(SessionUpdate.Record(record(2, "b")))
                val seen = mutableListOf<Long>()
                while (seen.size < 2) seen += batches.receive().map { it.seq }
                assertEquals(listOf(1L, 2L), seen)
            } finally {
                fixture.close()
            }
        }
    }

    private fun record(seq: Long, text: String, requestId: String? = null) =
        SessionRecord(
            seq,
            seq,
            "user/message",
            buildJsonObject {
                putJsonObject("source") {
                    put("kind", "user")
                    requestId?.let { put("rpcId", it) }
                }
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            },
        )

    private fun snapshot(lease: ConnectionGeneration, seq: Long, text: String) =
        SessionUpdate.Snapshot(
            lease,
            seq,
            listOf(record(seq, text)),
            true,
            JsonObject(emptyMap()),
            JsonObject(emptyMap()),
            null,
            false,
        )

    private fun catalogSource(title: String) =
        object : SessionHost() {
            override suspend fun list() =
                listOf(SessionSummary("session", title, null, false, false, true, null, null, 1))

            override suspend fun create(
                cwd: String?,
                workspaceId: String?,
                agentPreset: String?,
            ): String = error("unused")

            override suspend fun rename(id: String, title: String): String = error("unused")

            override suspend fun forkSession(id: String, atSeq: Long?): String = error("unused")

            override suspend fun forkState(
                id: String,
            ): dev.fughetta.dshcompose.domain.sessions.SessionForkState? = error("unused")

            override suspend fun updateQueuedPrompt(
                id: String,
                itemId: String,
                change: QueueChange,
            ): Unit = error("unused")

            override suspend fun createdAt(id: String): Long? = null

            override suspend fun delete(id: String): Unit = error("unused")
        }

    @Test
    fun transcriptOwnsCatalogSynchronizationEvenWhenPresentationIsPaused(): Unit = runBlocking {
        withTimeout(5000) {
            Fixture().use { fixture ->
                fixture.source.client.mux.ready()
                fixture.catalog.connect(fixture.source.client, fixture.scope)
                fixture.catalog.refresh()
                val gate = CompletableDeferred<Unit>()
                fixture.pause = gate
                val lease = fixture.source.reset(1)
                fixture.source.emit(snapshot(lease, 100, "initial"))
                fixture.publication { !it.syncing }
                fixture.source.emit(
                    SessionUpdate.Record(
                        SessionRecord(
                            101,
                            101,
                            "session/title",
                            buildJsonObject { put("title", "Updated") },
                        ),
                    ),
                )
                fixture.source.emit(
                    SessionUpdate.Record(
                        SessionRecord(102, 102, "turn/start", JsonObject(emptyMap())),
                    ),
                )
                while (true) {
                    val event = fixture.observed.receive()
                    if (event is SessionUpdate.Record && event.event.seq == 102L) break
                }
                assertEquals("Updated", fixture.catalog.get("session")!!.title)
                // Host status owns running; an unfinished transcript turn cannot replace it.
                assertFalse(fixture.catalog.get("session")!!.running)
                fixture.catalog.connect(catalogSource("Another connection"), fixture.scope)
                fixture.catalog.refresh()
                fixture.source.emit(
                    SessionUpdate.Record(
                        SessionRecord(
                            103,
                            103,
                            "session/title",
                            buildJsonObject { put("title", "Retired") },
                        ),
                    ),
                )
                while (true) {
                    val event = fixture.observed.receive()
                    if (event is SessionUpdate.Record && event.event.seq == 103L) break
                }
                assertEquals("Another connection", fixture.catalog.get("session")!!.title)
                gate.complete(Unit)
            }
        }
    }

    @Test
    fun historyPublishesThroughTheTranscriptStreamAndKeepsConcurrentLiveRecords() = runBlocking {
        withTimeout(5000) {
            Fixture().use { fixture ->
                val lease = fixture.source.reset(1)
                fixture.source.emit(snapshot(lease, 100, "initial"))
                val initial = fixture.publication { !it.syncing }
                val conversation: ConversationRepository = fixture.repository
                val read = async { conversation.loadHistory() }
                fixture.source.requested.receive()
                fixture.source.emit(SessionUpdate.Record(record(101, "live")))
                val live = fixture.publication {
                    it.presentation.items.any { row -> row.text == "live" }
                }
                fixture.source.response.complete(HistoryPage(listOf(record(1, "older")), false))
                read.await()
                val history = fixture.publication {
                    it.presentation.items.any { row -> row.text == "older" }
                }
                assertEquals(
                    listOf("older", "initial", "live"),
                    history.presentation.items.filter { it.role == ChatRole.User }.map { it.text },
                )
                assertEquals(initial.historyEpoch, history.historyEpoch)
                assertTrue(history.revision > live.revision)
                assertFalse(history.hasMoreHistory)
                val selected = ModelSelection("provider", "confirmed", "off")
                conversation.confirmModel(selected)
                conversation.confirmAgentPreset("preset")
                val confirmed = fixture.publication {
                    it.model == selected && it.agentPreset == "preset"
                }
                assertEquals(history.presentation.items, confirmed.presentation.items)
                assertTrue(confirmed.revision > history.revision)
            }
        }
    }

    @Test
    fun slowPublicationNeverDropsDurableRecordsOrPendingDeliveryProof() = runBlocking {
        withTimeout(5000) {
            Fixture().use { fixture ->
                val gate = CompletableDeferred<Unit>()
                fixture.pause = gate
                val lease = fixture.source.reset(1)
                fixture.source.emit(snapshot(lease, 100, "initial"))
                fixture.publication { !it.syncing }
                val pending =
                    fixture.pending.begin(
                        "host",
                        "/home",
                        "session",
                        "payload",
                        "draft",
                        null,
                        emptyList(),
                    )
                for (seq in 101L..120L) fixture.source.emit(
                    SessionUpdate.Record(
                        record(seq, "message $seq", if (seq == 120L) pending.requestId else null),
                    ),
                )
                while (true) {
                    val event = fixture.observed.receive()
                    if (event is SessionUpdate.Record && event.event.seq == 120L) break
                }
                assertTrue(
                    fixture.pending.wasConsumed(
                        PendingPromptsOwner("host", "/home", "session"),
                        pending.requestId,
                    ),
                )
                gate.complete(Unit)
                val final = fixture.publication {
                    it.presentation.items.count { row -> row.role == ChatRole.User } == 21
                }
                assertEquals(
                    "message 120",
                    final.presentation.items.last { it.role == ChatRole.User }.text,
                )
            }
        }
    }

    @Test
    fun resetWhileReadingHistoryDiscardsTheOldGenerationPage() = runBlocking {
        withTimeout(5000) {
            Fixture().use { fixture ->
                val first = fixture.source.reset(1)
                fixture.source.emit(snapshot(first, 100, "original"))
                val original = fixture.publication { !it.syncing }
                val read = async { fixture.repository.loadHistory() }
                assertEquals(100L to 100L, fixture.source.requested.receive())
                val second = fixture.source.reset(2)
                fixture.source.emit(snapshot(second, 100, "replacement"))
                val replaced = fixture.publication { it.generation == second && !it.syncing }
                assertTrue(replaced.historyEpoch > original.historyEpoch)
                fixture.source.response.complete(
                    HistoryPage(listOf(record(1, "obsolete history")), false),
                )
                read.await()
                fixture.source.emit(SessionUpdate.Record(record(101, "new event")))
                val latest = fixture.publication {
                    it.presentation.items.any { row -> row.text == "new event" }
                }
                assertFalse(latest.presentation.items.any { it.text == "obsolete history" })
                assertTrue(latest.hasMoreHistory)
            }
        }
    }

    @Test
    fun retiringConversationCancelsItsReaderAndHistoryWithoutCancellingTheParent() = runBlocking {
        withTimeout(5000) {
            Fixture().use { fixture ->
                val lease = fixture.source.reset(1)
                fixture.source.emit(snapshot(lease, 100, "initial"))
                fixture.publication { !it.syncing }
                val read = async { fixture.repository.loadHistory() }
                fixture.source.requested.receive()
                fixture.repository.close()
                try {
                    read.await()
                    fail("Retired history must be cancelled")
                } catch (_: CancellationException) {}
                fixture.source.cancelled.await()
                fixture.reader.join()
                // Permission observers belong to the connection and may finish after a chat
                // retires.
                fixture.repository.pendingApprovals(emptySet())
                assertTrue(fixture.scope.isActive)
            }
        }
    }
}

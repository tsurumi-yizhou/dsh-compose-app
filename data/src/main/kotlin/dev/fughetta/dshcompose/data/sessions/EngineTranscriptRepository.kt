package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.sync.TranscriptMirrorSink
import dev.fughetta.dshcompose.data.transcript.*
import dev.fughetta.dshcompose.domain.commands.CommandResult
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.TranscriptRetryPlan
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.usage.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.protocol.sessionSummaryFromSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * One selected conversation owns its reducer, stream, history reads and acknowledged local edits.
 */
class EngineTranscriptRepository
internal constructor(
    private val source: TranscriptDataSource,
    private val host: String,
    private val sessionId: String,
    parent: CoroutineScope,
    private val pendingMessages: EngineMessagesRepository,
    private val catalog: EngineSessionsRepository,
    private val clock: TranscriptClock,
    initialCommand: Pair<CommandResult, String>? = null,
    private val mirror: TranscriptMirrorSink? = null,
) : ConversationRepository {

    constructor(
        client: ProtocolClient,
        host: String,
        sessionId: String,
        parent: CoroutineScope,
        pendingMessages: EngineMessagesRepository,
        catalog: EngineSessionsRepository,
        clock: TranscriptClock,
        initialCommand: Pair<CommandResult, String>? = null,
        mirror: TranscriptMirrorSink? = null,
    ) : this(
        ClientTranscriptDataSource(client, sessionId),
        host,
        sessionId,
        parent,
        pendingMessages,
        catalog,
        clock,
        initialCommand,
        mirror,
    )

    private val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    /**
     * Mirror writes are serialized on one consumer and coalesced per wake-up: streaming emits many
     *
     * records per turn, and one Room write per record would be wasteful.
     */
    private val mirrorQueue: Channel<List<SessionRecord>>? = mirror?.let {
        Channel<List<SessionRecord>>(Channel.UNLIMITED)
    }

    init {

        val sink = mirror

        val queue = mirrorQueue

        if (sink != null && queue != null)
            scope.launch {
                for (first in queue) {

                    val batch = ArrayList(first)

                    while (true) batch.addAll(queue.tryReceive().getOrNull() ?: break)

                    runCatching { sink(host, sessionId, batch) }
                }
            }
    }

    private val mutex = Mutex()

    private val transcript =
        ChatTranscript().apply { initialCommand?.let { confirmCommand(it.first, it.second) } }

    private var syncing = true

    private var generation: ConnectionGeneration? = null

    private var pendingSummary: SessionSummary? = null

    private var sessionOrigin: String? = null

    private var permissionChanged = false

    private var activated: ConnectionGeneration? = null

    private val epochs = MutableStateFlow(clock.nextEpoch())

    private var epoch: Long
        get() = epochs.value
        set(value) {

            epochs.value = value
        }

    private val localChanges =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    @Volatile private var closed = false

    @Volatile private var reader: Job? = null

    override val historyEpoch = epochs.asStateFlow()

    override fun publications(
        isCurrentConnection: () -> Boolean,
        usage: UsageRepository?,
    ): Flow<TranscriptSnapshot> {
        fun current(generation: ConnectionGeneration?) =
            !closed && isCurrentConnection() && generation == source.generation

        return updates { event, generation ->
            if (usage != null) {
                val update =
                    when (event) {
                        is SessionUpdate.Reset -> UsageChange.Reset
                        is SessionUpdate.Snapshot ->
                            UsageChange.Snapshot(event.projections.toToolPayload())
                        is SessionUpdate.Record ->
                            if (
                                event.event.type in
                                    setOf(
                                        "user/message",
                                        "assistant/message",
                                        "turn/start",
                                        "turn/end",
                                        "request/context",
                                        "model/selection",
                                    )
                            )
                                UsageChange.Record(event.event.type)
                            else null
                        else -> null
                    }
                if (update != null)
                    withContext(Dispatchers.Main.immediate) {
                        if (current(generation)) usage.sessionUpdate(update)
                    }
            }
        }
            .filter { it.generation != null && current(it.generation) }
    }

    /**
     * Fold every event; only the immutable publication is conflated. The observer sees each event.
     */
    fun updates(
        onEvent: suspend (SessionUpdate, ConnectionGeneration?) -> Unit,
    ): Flow<TranscriptSnapshot> {

        val events =
            source
                .watch()
                .onStart {
                    check(reader == null) { "A transcript already has a reader" }

                    reader = currentCoroutineContext().job

                    ensureOpen()
                }
                .onEach { event ->
                    mutex.withLock {
                        ensureOpen()

                        if (event is SessionUpdate.Reset) {

                            epoch = clock.nextEpoch()

                            syncing = true

                            generation = event.generation

                            pendingSummary = null
                        }

                        if (
                            event is SessionUpdate.Snapshot ||
                                event is SessionUpdate.Record &&
                                    event.event.type in
                                        setOf(
                                            "permission/preset",
                                            "approval/policy",
                                            "sandbox/mode",
                                        )
                        )
                            permissionChanged = true

                        transcript.apply(event)

                        mirrorQueue?.let { queue ->
                            val records =
                                when (event) {
                                    is SessionUpdate.Snapshot -> event.records

                                    is SessionUpdate.Record -> listOf(event.event)

                                    else -> emptyList()
                                }

                            if (records.isNotEmpty()) queue.trySend(records)
                        }

                        generation?.let { lease ->
                            when (event) {
                                is SessionUpdate.Snapshot ->
                                    event.records.forEach {
                                        pendingMessages.observeRecord(
                                            host,
                                            lease.home,
                                            sessionId,
                                            it,
                                        )
                                    }

                                is SessionUpdate.Record ->
                                    pendingMessages.observeRecord(
                                        host,
                                        lease.home,
                                        sessionId,
                                        event.event,
                                    )

                                else -> Unit
                            }

                            if (
                                event is SessionUpdate.Snapshot ||
                                    event is SessionUpdate.Record &&
                                        event.event.type == "agent/inbox/spliced"
                            )
                                pendingMessages.observeInbox(
                                    host,
                                    lease.home,
                                    sessionId,
                                    transcript.inbox,
                                )
                        }

                        if (event is SessionUpdate.Snapshot) {

                            syncing = false

                            sessionOrigin =
                                (event.header["origin"] as? JsonPrimitive)?.contentOrNull

                            generation
                                ?.takeIf { it != activated }
                                ?.let { lease ->
                                    activated = lease

                                    scope.launch { source.activate(lease) }
                                }

                            pendingSummary =
                                sessionSummaryFromSnapshot(sessionId, event, transcript.running)
                                    .copy(
                                        title = transcript.title,
                                        agentAvailable =
                                            catalog.get(sessionId)?.agentAvailable == true,
                                    )

                            if (generation != null && generation == source.generation)
                                pendingSummary?.let {
                                    catalog.observedSnapshot(source.identity, it)
                                }
                        }

                        syncCatalogTitle()
                    }

                    onEvent(event, generation)
                }
                .flowOn(Dispatchers.Default)

        return channelFlow {
            val history =
                launch(start = CoroutineStart.UNDISPATCHED) { localChanges.collect { send(Unit) } }

            try {

                events.collect { send(Unit) }
            } finally {

                history.cancel()
            }
        }
            .conflate()
            .map { snapshot() }
    }

    private fun syncCatalogTitle() {

        if (generation == null || generation != source.generation || closed) return

        val title = transcript.title ?: return

        if (catalog.get(sessionId)?.title != title)
            catalog.observedTitle(source.identity, sessionId, title)
    }

    private fun ensureOpen() {

        if (closed) throw CancellationException("Conversation retired")
    }

    private suspend fun snapshot(): TranscriptSnapshot =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureOpen()

                TranscriptSnapshot(
                        generation,
                        clock.nextRevision(),
                        epoch,
                        transcript.presentation,
                        transcript.model,
                        transcript.agentPreset,
                        transcript.inbox,
                        transcript.title,
                        transcript.goal,
                        transcript.todos,
                        sessionOrigin,
                        transcript.running,
                        transcript.hasMoreHistory,
                        syncing,
                        pendingSummary,
                        permissionChanged,
                    )
                    .also {
                        pendingSummary = null

                        permissionChanged = false
                    }
            }
        }

    override suspend fun confirmModel(selection: ModelSelection) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!closed) {

                    transcript.confirmModel(selection)

                    localChanges.tryEmit(Unit)
                }
            }
        }

    override suspend fun confirmAgentPreset(preset: String) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!closed) {

                    transcript.confirmAgentPreset(preset)

                    localChanges.tryEmit(Unit)
                }
            }
        }

    override suspend fun confirmCommand(result: CommandResult, line: String) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!closed) {

                    transcript.confirmCommand(result, line)

                    localChanges.tryEmit(Unit)
                }
            }
        }

    override suspend fun pendingApprovals(callIds: Set<String>) =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!closed) {

                    transcript.pendingApprovals(callIds)

                    localChanges.tryEmit(Unit)
                }
            }
        }

    override suspend fun retryForNotice(key: String): TranscriptRetryPlan? =
        withContext(Dispatchers.Default) {
            mutex.withLock { if (closed) null else transcript.retryForNotice(key) }
        }

    override suspend fun branchPointAfter(seq: Long): Long? =
        withContext(Dispatchers.Default) {
            mutex.withLock { if (closed) null else transcript.branchPointAfter(seq) }
        }

    override suspend fun branchPointBefore(seq: Long): Long? = request {
        val address = mutex.withLock {
            ensureOpen()

            transcript.branchPointBefore(seq)?.let {
                return@request it
            }

            val lease = generation ?: return@request null

            HistoryAddress(seq - 1, null, epoch, lease)
        }

        val page = source.history(address.cursor, address.beforeSeq)

        mutex.withLock {
            ensureOpen()

            if (!current(address)) null else forkPointBeforePrompt(page.records.asReversed(), seq)
        }
    }

    private data class HistoryAddress(
        val cursor: Long,
        val beforeSeq: Long?,
        val epoch: Long,
        val generation: ConnectionGeneration,
    )

    private fun current(address: HistoryAddress): Boolean =
        epoch == address.epoch &&
            generation == address.generation &&
            source.generation == address.generation

    override suspend fun loadHistory() {

        try {

            request {
                val address = mutex.withLock {
                    ensureOpen()

                    if (syncing || !transcript.hasMoreHistory) return@request

                    val oldest = transcript.oldestSeq ?: return@request

                    val lease = generation ?: return@request

                    HistoryAddress(transcript.cursor, oldest, epoch, lease)
                }

                val page = source.history(address.cursor, address.beforeSeq)

                mutex.withLock {
                    ensureOpen()

                    if (!current(address)) return@withLock

                    transcript.prependHistory(page.records, page.hasMore)

                    syncCatalogTitle()

                    localChanges.tryEmit(Unit)
                }
            }
        } catch (error: CancellationException) {

            throw error
        } catch (error: SessionOperationException) {

            throw error
        } catch (error: Exception) {

            throw SessionOperationException(sessionDeletionFailure(error), error)
        }
    }

    private suspend fun <T> request(operation: suspend () -> T): T {

        val task = scope.async(Dispatchers.Default) { operation() }

        return try {

            task.await()
        } finally {

            task.cancel()
        }
    }

    override fun close() {

        closed = true

        mirrorQueue?.close()

        reader?.cancel()

        scope.cancel()
    }
}

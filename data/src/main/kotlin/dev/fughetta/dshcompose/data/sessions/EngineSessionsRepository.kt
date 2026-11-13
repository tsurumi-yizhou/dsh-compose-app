package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.commands.CommandCatalogs
import dev.fughetta.dshcompose.data.database.OutboxRow
import dev.fughetta.dshcompose.data.sessions.search.EngineSessionSearchRepository
import dev.fughetta.dshcompose.data.sync.Delivery
import dev.fughetta.dshcompose.data.sync.SessionCache
import dev.fughetta.dshcompose.data.sync.SessionMirror
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.SessionOperationException
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.protocol.decodeSessionSummary
import java.util.UUID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.*

/** The selected connection owns reads, acknowledged edits, and its event subscription. */
class EngineSessionsRepository(
    private val cache: SessionCache? = null,
    private val cacheScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SessionsRepository, AutoCloseable {
    override suspend fun <T : AutoCloseable, R> transferArchive(
        session: String,
        destination: suspend () -> T,
        output: (T) -> dev.fughetta.dshcompose.domain.ByteSink,
        commit: suspend (T) -> R,
        current: () -> Boolean,
        progress: (Long, Long?) -> Unit,
    ): SessionArchiveReceipt<R>? =
        transferSessionArchive(this, session, destination, output, commit, current, progress)

    internal class Owner(
        val client: ProtocolClient,
        parent: CoroutineScope,
    ) {
        val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        val commands = CommandCatalogs().apply { connect(client, scope) }
        val generation = client.mux.state.value.generation
        val state = MutableStateFlow(SessionCatalogState(available = true, loading = true))
        val inventory = SessionInventory()
        val retired = hashSetOf<String>()
        // Creation headers are immutable. Missing headers and read failures are retried on
        // reconnect.
        val creationTimes = mutableMapOf<String, Deferred<Long?>>()
        val creationReads = Semaphore(2)
        val all = SessionInventory()
        var read: Deferred<Unit>? = null
        var scheduled: Job? = null
        var dirty = false
        var loading = false
        var opening = true
        var initialized = false
        var refreshRevision = 0L
        var failure: SessionOperationException? = null

        fun upsert(row: SessionSummary) {
            all.upsert(row)
            if (row.origin != "subagent") inventory.upsert(row)
        }

        fun rename(id: String, title: String) {
            all.renamed(id, title)
            inventory.renamed(id, title)
        }

        fun remove(id: String) {
            all.removed(id)
            inventory.removed(id)
        }
    }

    internal val lock = Any()
    private var owner: Owner? = null
    private var cachedWatch: Job? = null
    private val writes = cache
    private var lastHostId: String? = null
    private val mutable = MutableStateFlow(SessionCatalogState())
    override val state = mutable.asStateFlow()
    internal val search = EngineSessionSearchRepository(cacheScope)
    private val mutableConnection = MutableStateFlow<SessionsRepository?>(null)
    override val connection = mutableConnection.asStateFlow()

    fun connect(client: ProtocolClient, parent: CoroutineScope, hostId: String? = null) {
        lastHostId = hostId
        val next = Owner(client, parent)
        search.connect(client, hostId.orEmpty(), parent)
        val previous =
            synchronized(lock) {
                owner.also {
                    owner = next
                    mutable.value = next.state.value
                    mutableConnection.value = connectedRepository(next)
                }
            }
        previous?.commands?.close()
        previous?.scope?.cancel()
        cachedWatch?.cancel()
        cachedWatch = null
        val writes = writes
        val host = lastHostId
        if (writes != null && host != null) {
            next.scope.launch {
                // A previous connection may have dropped mid-write; those rows retry first with the
                // same receipt id so the engine can de-duplicate.
                runCatching { writes.retryUnknown(host) }
                runCatching {
                    val report = writes.drainWrites(host, writeDelivery(next))
                    // The host gave a final answer: the row stops showing "waiting to sync".
                    for (sessionId in report.settledSessions.distinct()) runCatching {
                        writes.markSynced(host, sessionId)
                    }
                }
                runCatching { refresh() }
                runCatching { reconcilePendingWrites(host) }
            }
        }
    }

    /**
     * A process that died between the host's answer and [SessionCache.markSynced] leaves a "waiting
     * to sync" marker with nothing left in the outbox. The next connection clears those rows; a
     * rename that is still queued keeps its marker.
     */
    internal suspend fun reconcilePendingWrites(host: String) {
        val cached = runCatching { cache?.cached(host) }.getOrNull() ?: return
        val writes = writes ?: return
        val pending = cached.filter { it.pendingWrite }.map { it.sessionId }
        if (pending.isEmpty()) return
        val outstanding = writes.outstandingRenameSessions(host)
        for (sessionId in pending) if (sessionId !in outstanding) writes.markSynced(host, sessionId)
    }

    fun disconnect(loading: Boolean = false) {
        val previous =
            synchronized(lock) {
                owner.also {
                    owner = null
                    mutableConnection.value = null
                    mutable.value = SessionCatalogState(loading = loading)
                }
            }
        previous?.scope?.cancel()
        search.connect(null, lastHostId.orEmpty(), cacheScope)
        publishCached(loading)
    }

    /**
     * Renames a session without a link: the mirrored row shows the new title immediately and the
     * outbox replays the write after the next successful connection. Valid only while disconnected;
     * with a link the engine answer is authoritative.
     */
    override suspend fun renameOffline(sessionId: String, title: String): String {
        val at = System.currentTimeMillis()
        val writes = writes ?: throw ConnectionClosedException()
        val host = lastHostId ?: throw ConnectionClosedException()
        if (synchronized(lock) { owner != null }) throw ConnectionClosedException()
        val trimmed = title.trim()
        if (trimmed.isEmpty())
            throw SessionOperationException("session/title-required", IllegalArgumentException())
        writes.renameLocal(host, sessionId, trimmed, at)
        writes.enqueueRename(host, sessionId, trimmed, UUID.randomUUID().toString())
        synchronized(lock) {
            mutable.value =
                mutable.value.copy(
                    sessions =
                        mutable.value.sessions
                            .map { if (it.sessionId == sessionId) it.copy(title = trimmed) else it }
                            .toPersistentList(),
                    allSessions =
                        mutable.value.allSessions
                            .map { if (it.sessionId == sessionId) it.copy(title = trimmed) else it }
                            .toPersistentList(),
                )
        }
        return trimmed
    }

    /** Replays one outbox row against the freshly connected engine. */
    private fun writeDelivery(current: Owner): suspend (OutboxRow) -> Delivery = { row ->
        when (row.kind) {
            SessionMirror.KIND_RENAME -> {
                val payload = runCatching {
                    Json.parseToJsonElement(row.payload).jsonObject
                }
                    .getOrNull()
                val id = payload?.get("sessionId")?.jsonPrimitive?.contentOrNull
                val title = payload?.get("title")?.jsonPrimitive?.contentOrNull
                when {
                    id == null || title == null -> Delivery.Acknowledged
                    else ->
                        try {
                            current.client.renameSession(id, title)
                            Delivery.Acknowledged
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: HostRemoteException) {
                            // The host answered no: keep the row for the user, never retry blindly.
                            Delivery.Rejected(error.code)
                        } catch (_: Exception) {
                            Delivery.Unknown
                        }
                }
            }
            else -> Delivery.Rejected("sessions/unsupported-write")
        }
    }

    /** Write-through, best effort: the engine answer is persisted for the next offline start. */
    private suspend fun storeCached(rows: List<SessionSummary>) {
        val cache = cache ?: return
        val host = lastHostId ?: return
        runCatching { cache.store(host, rows) }
    }

    /**
     * Offline: the mirrored directory stays readable while no link is up, and keeps updating when
     * something else writes the mirror (background sync, another screen), so the list never goes
     * stale on screen.
     */
    private fun publishCached(loading: Boolean) {
        val cache = cache ?: return
        val host = lastHostId ?: return
        cachedWatch?.cancel()
        cachedWatch = cacheScope.launch {
            cache.watch(host).collect { rows ->
                synchronized(lock) {
                    if (owner != null) return@synchronized
                    mutable.value =
                        SessionCatalogState(
                            available = false,
                            sessions = rows.filter { it.origin != "subagent" }.toPersistentList(),
                            allSessions = rows.toPersistentList(),
                            loading = loading,
                            initialized = true,
                        )
                }
            }
        }
    }

    fun connectionAttemptFinished() =
        synchronized(lock) {
            owner?.let { current ->
                current.opening = false
                publish(current)
            } ?: run { mutable.value = mutable.value.copy(loading = false) }
        }

    fun operationsFor(client: ProtocolClient): SessionsRepository? = operationsFor(client as Any)

    internal fun operationsFor(identity: Any): SessionsRepository? =
        synchronized(lock) { if (owner?.client === identity) mutableConnection.value else null }

    fun snapshot(): PersistentList<SessionSummary> = state.value.sessions

    override fun get(id: String): SessionSummary? = synchronized(lock) { owner?.inventory?.get(id) }

    override suspend fun refresh() {
        try {
            val current = synchronized(lock) { owner ?: throw ConnectionClosedException() }
            refresh(current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SessionOperationException) {
            throw error
        } catch (error: Exception) {
            val failure = SessionOperationException(sessionDeletionFailure(error), error)
            synchronized(lock) {
                if (owner == null)
                    mutable.value = mutable.value.copy(failure = failure, loading = false)
            }
            throw failure
        }
    }

    override val hostHome: String?
        get() = synchronized(lock) { owner?.generation?.home }

    override val generation: Any?
        get() = synchronized(lock) { owner?.generation }

    override suspend fun list(): List<SessionSummary> =
        synchronized(lock) { owner }?.client?.listSessions() ?: throw ConnectionClosedException()

    override val active: Boolean
        get() = synchronized(lock) { owner?.let(::isCurrent) == true }

    private fun operations(): SessionsRepository =
        synchronized(lock) { owner?.let { connectedRepository(it) } }
            ?: throw ConnectionClosedException()

    override suspend fun create(
        cwd: String?,
        workspaceId: String?,
        agentPreset: String?,
    ): String = operations().create(cwd, workspaceId, agentPreset)

    override suspend fun rename(sessionId: String, title: String): String =
        operations().rename(sessionId, title)

    override suspend fun forkSession(sessionId: String, atSeq: Long?): String =
        operations().forkSession(sessionId, atSeq)

    override suspend fun forkState(sessionId: String): SessionForkState? =
        operations().forkState(sessionId)

    override suspend fun updateQueuedPrompt(
        sessionId: String,
        itemId: String,
        change: QueueChange,
    ) = operations().updateQueuedPrompt(sessionId, itemId, change)

    override suspend fun commandCatalog(sessionId: String) = operations().commandCatalog(sessionId)

    override suspend fun executeCommand(sessionId: String, line: String) =
        operations().executeCommand(sessionId, line)

    override fun selectCommandSession(sessionId: String?) {
        synchronized(lock) { owner?.commands?.connection?.selectSession(sessionId) }
    }

    override fun invalidateCommands() {
        synchronized(lock) { owner?.commands?.connection?.invalidate() }
    }

    override suspend fun createdAt(sessionId: String): Long? = operations().createdAt(sessionId)

    override suspend fun delete(sessionId: String) = operations().delete(sessionId)

    override val supported: Boolean
        get() = synchronized(lock) { owner?.client?.canExportSessionLog == true }

    override suspend fun exportLog(
        sessionId: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long = operations().exportLog(sessionId, destination, onProgress)

    override fun pending(eventId: String): Boolean =
        synchronized(lock) { owner?.client }?.mux?.pendingRequests?.value?.contains(eventId) == true

    override fun remaining(sessionId: String, callId: String): kotlinx.coroutines.flow.Flow<Long?> =
        flow {
            val client = synchronized(lock) { owner?.client } ?: throw ConnectionClosedException()
            emitAll(
                client.claimQuestionWait(sessionId, callId).map { event ->
                    (event as? MuxEvent.Item)?.let { item ->
                        ((item.value as? JsonObject)?.get("remainingMs") as? JsonPrimitive)
                            ?.longOrNull
                    }
                },
            )
        }

    override val context
        get() = search.context

    override val catalogRevision
        get() = search.catalogRevision

    override suspend fun results(query: String, hits: List<SessionContentHit>) =
        search.results(query, hits)

    override val searchActive: Boolean
        get() = search.active

    override suspend fun search(query: String): SessionSearchPage = search.search(query)

    override fun indexSessions(rows: PersistentList<SessionSummary>, archived: Set<String>) =
        search.inventory(rows, archived)

    internal suspend fun refresh(current: Owner) {
        val read =
            synchronized(lock) {
                requireCurrent(current)
                current.read?.takeIf { it.isActive }
                    ?: run {
                        val point = current.inventory.beginRead()
                        val allPoint = current.all.beginRead()
                        val task =
                            current.scope.async<Unit>(
                                Dispatchers.Default,
                                start = CoroutineStart.LAZY,
                            ) {
                                try {
                                    val rows = current.client.listSessions()
                                    synchronized(lock) {
                                        requireCurrent(current)
                                        current.all.replace(
                                            rows.filter { it.sessionId !in current.retired },
                                            allPoint,
                                        )
                                        current.inventory.replace(
                                            rows.filter {
                                                it.origin != "subagent" &&
                                                    it.sessionId !in current.retired
                                            },
                                            point,
                                        )
                                        current.failure = null
                                        current.refreshRevision++
                                    }
                                    storeCached(rows)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    synchronized(lock) {
                                        if (owner === current)
                                            current.failure =
                                                SessionOperationException(
                                                    sessionDeletionFailure(error),
                                                    error,
                                                )
                                    }
                                    throw error
                                } finally {
                                    synchronized(lock) {
                                        if (owner === current) {
                                            current.loading = false
                                            current.opening = false
                                            current.initialized = true
                                            current.read = null
                                            publish(current)
                                        }
                                    }
                                }
                            }
                        current.read = task
                        current.loading = current.inventory.snapshot().isEmpty()
                        publish(current)
                        task.start()
                        task
                    }
            }
        read.await()
    }

    override fun scheduleRefresh() =
        synchronized(lock) {
            val current = owner ?: return@synchronized
            current.dirty = true
            if (current.scheduled?.isActive == true) return@synchronized
            current.scheduled =
                current.scope.launch {
                    do {
                        delay(500)
                        synchronized(lock) { current.dirty = false }
                        try {
                            refresh(current)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // refresh publishes the failure; a later host event can request another
                            // read.
                        }
                    } while (synchronized(lock) { owner === current && current.dirty })
                }
        }

    fun onHostEvent(client: ProtocolClient, event: JsonObject) =
        synchronized(lock) {
            val current = owner?.takeIf { it.client === client } ?: return@synchronized
            val args = event["args"] as? JsonArray ?: return@synchronized
            when ((event["event"] as? JsonPrimitive)?.contentOrNull) {
                "api-session/added" -> {
                    val row =
                        try {
                            decodeSessionSummary(args.firstOrNull() ?: return@synchronized)
                        } catch (_: HostException) {
                            current.failure =
                                SessionOperationException(
                                    "protocol-error",
                                    HostProtocolException("session/invalid-summary"),
                                )
                            publish(current)
                            return@synchronized
                        }
                    if (row.sessionId !in current.retired) current.upsert(row)
                }
                "api-session/status" -> {
                    val id =
                        (args.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return@synchronized
                    val running =
                        (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull ?: return@synchronized
                    val row = current.inventory.get(id)
                    val known = current.all.get(id)
                    if (known != null && known.running != running)
                        current.upsert(known.copy(running = running))
                    if (!running || row == null || row.blank || !row.agentAvailable)
                        scheduleRefresh()
                }
                "api-session/removed" -> {
                    val id =
                        (args.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: return@synchronized
                    current.remove(id)
                }
                else -> return@synchronized
            }
            publish(current)
        }

    internal fun observedSnapshot(identity: Any, summary: SessionSummary) =
        synchronized(lock) {
            val current = owner?.takeIf { it.client === identity } ?: return@synchronized
            if (summary.origin == "subagent" || summary.sessionId in current.retired)
                return@synchronized
            val previous = current.inventory.get(summary.sessionId)
            current.upsert(
                if (previous == null) summary else summary.copy(running = previous.running),
            )
            publish(current)
        }

    internal fun observedTitle(identity: Any, id: String, title: String) =
        synchronized(lock) {
            val current = owner?.takeIf { it.client === identity } ?: return@synchronized
            current.rename(id, title)
            publish(current)
        }

    internal fun publish(current: Owner) {
        val sessions = current.inventory.snapshot()
        current.state.value =
            SessionCatalogState(
                available = current.scope.isActive,
                sessions = sessions,
                allSessions = current.all.snapshot(),
                loading = current.loading || current.opening,
                initialized = current.initialized,
                refreshRevision = current.refreshRevision,
                failure = current.failure,
            )
        mutable.value = current.state.value
    }

    internal suspend fun ProtocolClient.readForkState(id: String): SessionForkState? {
        val values = projections(id)?.get("values") as? JsonObject ?: return null
        val inbox = values["inbox"] as? JsonObject
        return SessionForkState(
            (values["title"] as? JsonPrimitive)?.contentOrNull,
            listOf("next-step", "next-turn").flatMap { slot ->
                (inbox?.get(slot) as? JsonArray).orEmpty().map {
                    ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                }
            },
        )
    }

    internal suspend fun ProtocolClient.changeQueue(
        id: String,
        itemId: String,
        change: QueueChange,
    ) {
        connection.call(
            "session/updateQueue",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", id)
                    put("itemId", itemId)
                    putJsonObject("action") {
                        when (change) {
                            QueueChange.Remove -> put("kind", "remove")
                            QueueChange.Steer -> put("kind", "steer")
                            is QueueChange.Edit -> {
                                put("kind", "edit")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", change.text)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    internal suspend fun ProtocolClient.readCreatedAt(id: String): Long? =
        history(id, throughSeq = 0).records.firstOrNull { it.seq == 0L }?.time?.takeIf { it > 0 }

    internal fun isCurrent(current: Owner): Boolean =
        owner === current &&
            current.scope.isActive &&
            current.client.mux.state.value.generation == current.generation

    internal fun requireCurrent(current: Owner) {
        if (!isCurrent(current)) throw ConnectionClosedException()
    }

    override fun close() {
        disconnect()
        search.close()
        cachedWatch?.cancel()
        cachedWatch = null
    }
}

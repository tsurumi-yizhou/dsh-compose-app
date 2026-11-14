package dev.fughetta.dshcompose.testing

import dev.fughetta.dshcompose.data.sessions.transferSessionArchive
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*

/**
 * The connection-backed inventory part of the contract, for controller tests with a mock client.
 */
class ClientSessions(private val client: ProtocolClient) : SessionsRepository {
    override suspend fun commandCatalog(
        sessionId: String,
    ): dev.fughetta.dshcompose.domain.commands.CommandCatalog = error("unused")

    override suspend fun executeCommand(
        sessionId: String,
        line: String,
    ): dev.fughetta.dshcompose.domain.commands.CommandResult = error("unused")

    override fun selectCommandSession(sessionId: String?) = Unit

    override fun invalidateCommands() = Unit

    override fun scheduleRefresh() = Unit

    override suspend fun renameOffline(sessionId: String, title: String): String = error("unused")

    override suspend fun <T : AutoCloseable, R> transferArchive(
        session: String,
        destination: suspend () -> T,
        output: (T) -> dev.fughetta.dshcompose.domain.ByteSink,
        commit: suspend (T) -> R,
        current: () -> Boolean,
        progress: (Long, Long?) -> Unit,
    ): SessionArchiveReceipt<R>? =
        transferSessionArchive(this, session, destination, output, commit, current, progress)

    override val connection = MutableStateFlow<SessionsRepository?>(null)

    override val hostHome: String?
        get() = client.mux.state.value.generation?.home

    override val generation: Any?
        get() = client.mux.state.value.generation

    override val state = MutableStateFlow(SessionCatalogState())

    override val active = true

    override fun get(id: String): SessionSummary? = null

    override suspend fun refresh() {}

    override suspend fun list(): List<SessionSummary> = client.listSessions()

    override suspend fun create(
        cwd: String?,
        workspaceId: String?,
        agentPreset: String?,
    ): String =
        client.createSession(cwd = cwd, workspaceId = workspaceId, agentPreset = agentPreset)

    override suspend fun rename(sessionId: String, title: String): String = error("unused")

    override suspend fun forkSession(sessionId: String, atSeq: Long?): String = error("unused")

    override suspend fun forkState(sessionId: String): SessionForkState? = error("unused")

    override suspend fun updateQueuedPrompt(
        sessionId: String,
        itemId: String,
        change: QueueChange,
    ) {}

    override suspend fun createdAt(sessionId: String): Long? = null

    override suspend fun delete(sessionId: String) {}

    override val supported: Boolean
        get() = client.canExportSessionLog

    override suspend fun exportLog(
        sessionId: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long =
        try {
            client.exportSessionLog(
                sessionId,
                object : java.io.OutputStream() {
                    override fun write(value: Int) = destination(byteArrayOf(value.toByte()), 0, 1)

                    override fun write(bytes: ByteArray, offset: Int, length: Int) =
                        destination(bytes, offset, length)
                },
                onProgress,
            )
        } catch (error: HostRemoteException) {
            throw dev.fughetta.dshcompose.domain.OperationFailure(error.code, error)
        }

    override fun pending(eventId: String): Boolean = eventId in client.mux.pendingRequests.value

    override fun remaining(sessionId: String, callId: String): Flow<Long?> =
        client.claimQuestionWait(sessionId, callId).map { event ->
            (event as? MuxEvent.Item)?.let { item ->
                ((item.value as? JsonObject)?.get("remainingMs") as? JsonPrimitive)?.longOrNull
            }
        }

    override val context: StateFlow<SearchContext> = MutableStateFlow(SearchContext())

    override val catalogRevision: StateFlow<Long> = MutableStateFlow(0L)

    override suspend fun results(
        query: String,
        hits: List<SessionContentHit>,
    ): SessionSearchResults = error("unused")

    override val searchActive = false

    override suspend fun search(query: String): SessionSearchPage = error("unused")

    override fun indexSessions(rows: PersistentList<SessionSummary>, archived: Set<String>) {}
}

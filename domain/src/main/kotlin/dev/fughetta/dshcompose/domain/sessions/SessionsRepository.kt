package dev.fughetta.dshcompose.domain.sessions

import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.search.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The host's conversation directory, creation, log export, host-side question waits, search and the
 * acknowledged session mutations. Each host connection publishes a distinct repository through
 * [connection]; a retired repository never serves a replacement connection.
 */
interface SessionsRepository {
    suspend fun commandCatalog(
        sessionId: String,
    ): dev.fughetta.dshcompose.domain.commands.CommandCatalog

    suspend fun executeCommand(
        sessionId: String,
        line: String,
    ): dev.fughetta.dshcompose.domain.commands.CommandResult

    fun selectCommandSession(sessionId: String?)

    fun invalidateCommands()

    suspend fun <T : AutoCloseable, R> transferArchive(
        session: String,
        destination: suspend () -> T,
        output: (T) -> dev.fughetta.dshcompose.domain.ByteSink,
        commit: suspend (T) -> R,
        current: () -> Boolean,
        progress: (Long, Long?) -> Unit,
    ): SessionArchiveReceipt<R>?

    val connection: StateFlow<SessionsRepository?>

    /** Null before the first successful connection. */
    val hostHome: String?

    /** Opaque link token: equal only while the same host connection is live. */
    val generation: Any?

    val state: StateFlow<SessionCatalogState>

    val active: Boolean

    fun get(id: String): SessionSummary?

    fun scheduleRefresh()

    suspend fun renameOffline(sessionId: String, title: String): String

    suspend fun refresh()

    suspend fun list(): List<SessionSummary>

    /** Creates a new conversation on the host, optionally rooted at [cwd] or in [workspaceId]. */
    suspend fun create(
        cwd: String? = null,
        workspaceId: String? = null,
        agentPreset: String? = null,
    ): String

    suspend fun rename(sessionId: String, title: String): String

    suspend fun forkSession(sessionId: String, atSeq: Long? = null): String

    suspend fun forkState(sessionId: String): SessionForkState?

    suspend fun updateQueuedPrompt(
        sessionId: String,
        itemId: String,
        change: QueueChange = QueueChange.Remove,
    )

    /** First recorded event time, without activating the session; null when no header. */
    suspend fun createdAt(sessionId: String): Long?

    suspend fun delete(sessionId: String)

    /** False when the current carrier cannot stream session archives. */
    val supported: Boolean

    suspend fun exportLog(
        sessionId: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): Long

    fun pending(eventId: String): Boolean

    /** Emits the remaining milliseconds of one claimed wait. */
    fun remaining(sessionId: String, callId: String): Flow<Long?>

    /** The host and connection generation the content search belongs to. */
    val context: StateFlow<SearchContext>

    val catalogRevision: StateFlow<Long>

    suspend fun results(query: String, hits: List<SessionContentHit>): SessionSearchResults

    val searchActive: Boolean

    suspend fun search(query: String): SessionSearchPage

    /** Feeds the search index with the catalog snapshot and the app's archived-session set. */
    fun indexSessions(rows: PersistentList<SessionSummary>, archived: Set<String>)
}

data class SessionCatalogState(
    /** Whether catalog reads have a connection owner. */
    val available: Boolean = false,
    val sessions: PersistentList<SessionSummary> = persistentListOf(),
    val allSessions: PersistentList<SessionSummary> = persistentListOf(),
    val loading: Boolean = false,
    val initialized: Boolean = false,
    val refreshRevision: Long = 0,
    val failure: SessionOperationException? = null,
)

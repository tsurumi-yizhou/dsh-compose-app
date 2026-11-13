package dev.fughetta.dshcompose.domain.sessions

import dev.fughetta.dshcompose.domain.ByteSink
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.search.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Contract implementation for domain tests; members under test are overridden by each test. */
internal open class TestSessionsRepository : SessionsRepository {
    override suspend fun commandCatalog(sessionId: String): CommandCatalog = error("unused")

    override suspend fun executeCommand(sessionId: String, line: String): CommandResult =
        error("unused")

    override fun selectCommandSession(sessionId: String?) = Unit

    override fun invalidateCommands() = Unit

    override fun scheduleRefresh() = Unit

    override suspend fun renameOffline(sessionId: String, title: String): String = error("unused")

    override suspend fun <T : AutoCloseable, R> transferArchive(
        session: String,
        destination: suspend () -> T,
        output: (T) -> ByteSink,
        commit: suspend (T) -> R,
        current: () -> Boolean,
        progress: (Long, Long?) -> Unit,
    ): SessionArchiveReceipt<R>? = error("unused")

    override val connection = MutableStateFlow<SessionsRepository?>(null)

    override val hostHome: String? = null

    override val generation: Any? = null

    override val state = MutableStateFlow(SessionCatalogState())

    override var active = true

    override fun get(id: String): SessionSummary? = null

    override suspend fun refresh() {}

    override suspend fun list(): List<SessionSummary> = emptyList()

    override suspend fun create(
        cwd: String?,
        workspaceId: String?,
        agentPreset: String?,
    ): String = error("unused")

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

    override val supported = false

    override suspend fun exportLog(
        sessionId: String,
        destination: ByteSink,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long = error("unused")

    override fun pending(eventId: String): Boolean = false

    override fun remaining(sessionId: String, callId: String): Flow<Long?> = emptyFlow()

    override val context = MutableStateFlow(SearchContext())

    override val catalogRevision = MutableStateFlow(0L)

    override suspend fun results(
        query: String,
        hits: List<SessionContentHit>,
    ): SessionSearchResults = error("unused")

    override val searchActive = false

    override suspend fun search(query: String): SessionSearchPage = error("unused")

    override fun indexSessions(rows: PersistentList<SessionSummary>, archived: Set<String>) {}
}

package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * Presents a mirrored transcript with no connection behind it. It emits one synthetic snapshot so
 * the existing fold produces the same presentation the live path would; the conversation is
 * read-only because nothing can be sent or paginated while this source is bound.
 */
class CachedTranscriptDataSource(
    private val sessionId: String,
    private val records: List<SessionRecord>,
    home: String,
    private val hasMore: Boolean = false,
    private val older: (suspend (beforeSeq: Long, limit: Int) -> List<SessionRecord>)? = null,
) : TranscriptDataSource() {
    private val lease = ConnectionGeneration(0, CACHED_CLIENT_ID, home)
    override val identity: Any = this
    override val generation: ConnectionGeneration? = lease

    override fun watch(): Flow<SessionUpdate> = flow {
        emit(SessionUpdate.Reset(lease))
        emit(
            SessionUpdate.Snapshot(
                generation = lease,
                cursor = records.lastOrNull()?.seq ?: 0,
                records = records,
                hasMore = hasMore && older != null,
                header = JsonObject(emptyMap()),
                projections = JsonObject(emptyMap()),
                assistantStream = null,
                historyReplaced = true,
            ),
        )
        awaitCancellation()
    }

    override suspend fun history(throughSeq: Long, beforeSeq: Long?): HistoryPage {
        val load = older ?: return HistoryPage(emptyList(), hasMore = false)
        if (beforeSeq == null) return HistoryPage(emptyList(), hasMore = false)
        val page = load(beforeSeq, HISTORY_PAGE)
        return HistoryPage(page, hasMore = page.size >= HISTORY_PAGE)
    }

    companion object {
        const val CACHED_CLIENT_ID = "offline"
        private const val HISTORY_PAGE = 100
    }

    override suspend fun activate(generation: ConnectionGeneration) = Unit
}

/** Builds a read-only repository over a mirrored transcript; no engine call is made. */
fun cachedTranscriptRepository(
    host: String,
    sessionId: String,
    home: String,
    records: List<SessionRecord>,
    parent: CoroutineScope,
    pendingMessages: EngineMessagesRepository,
    catalog: EngineSessionsRepository,
    clock: TranscriptClock,
    hasMore: Boolean = false,
    older: (suspend (beforeSeq: Long, limit: Int) -> List<SessionRecord>)? = null,
): EngineTranscriptRepository =
    EngineTranscriptRepository(
        CachedTranscriptDataSource(sessionId, records, home, hasMore, older),
        host,
        sessionId,
        parent,
        pendingMessages,
        catalog,
        clock,
    )

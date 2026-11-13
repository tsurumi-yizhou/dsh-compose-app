package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.SessionMirrorDao
import dev.fughetta.dshcompose.data.database.SessionRow
import dev.fughetta.dshcompose.data.database.SyncStates
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read API of the local session catalog: the UI observes Room and never the engine connection.
 * [refresh] is the only path that writes engine data; the engine answer only reaches readers after
 * it is persisted, which is what keeps the local mirror the single source of truth.
 */
class LocalSessionCatalog(
    private val sessions: SessionMirrorDao,
    private val directory: SessionDirectorySync,
) {
    fun watch(hostId: String): Flow<List<SessionSummary>> =
        sessions.watch(hostId).map { rows -> rows.map(SessionRow::asSummary) }

    /** Shows a new title before the engine confirms it; the row keeps its pending state. */
    suspend fun renameLocal(hostId: String, sessionId: String, title: String, at: Long) =
        sessions.rename(hostId, sessionId, title, SyncStates.PENDING, at)

    /** The host answered the queued write: the row is no longer pending either way. */
    suspend fun markSynced(hostId: String, sessionId: String) =
        sessions.setState(hostId, sessionId, SyncStates.ACKED)

    /** Drops the mirrored directory rows of a host that is being removed. */
    suspend fun clear(hostId: String) {
        sessions.clear(hostId)
    }

    suspend fun cached(hostId: String): List<SessionSummary> =
        sessions.list(hostId).map(SessionRow::asSummary)

    suspend fun refresh(hostId: String, fetch: suspend () -> List<SessionSummary>): SyncReport =
        directory.sync(hostId, fetch)
}

private fun SessionRow.asSummary() =
    SessionSummary(
        sessionId = sessionId,
        title = title.takeIf { it.isNotEmpty() },
        cwd = null,
        running = running,
        blank = false,
        agentAvailable = true,
        parentSessionId = null,
        origin = null,
        updatedAt = updatedAt,
        pendingWrite = syncState == SyncStates.PENDING,
    )

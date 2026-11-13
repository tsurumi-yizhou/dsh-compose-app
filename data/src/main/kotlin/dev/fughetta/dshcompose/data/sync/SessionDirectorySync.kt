package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.SessionMirrorDao
import dev.fughetta.dshcompose.data.database.SessionRow
import dev.fughetta.dshcompose.data.database.SyncCursorDao
import dev.fughetta.dshcompose.data.database.SyncCursorRow
import dev.fughetta.dshcompose.data.database.SyncStates
import dev.fughetta.dshcompose.domain.sessions.SessionSummary

/** Cursor stream of the engine session directory. */
const val DIRECTORY_STREAM = "directory"

/**
 * What one directory pass did; `written` is 0 when the mirror was already current and `removed`
 * holds the sessions the engine no longer lists (they are deleted from the mirror).
 */
data class SyncReport(
    val written: Int,
    val total: Int,
    val removed: List<String> = emptyList(),
)

/**
 * Reconciles the engine session directory into the local mirror. The engine answers with the whole
 * list, so only the rows whose engine-owned fields changed are written; a row that still has a
 * local write outstanding keeps the `syncState` its outbox cycle assigned.
 */
class SessionDirectorySync(
    private val sessions: SessionMirrorDao,
    private val cursors: SyncCursorDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(hostId: String, fetch: suspend () -> List<SessionSummary>): SyncReport {
        val remote = fetch()
        val local = sessions.list(hostId).associateBy { it.sessionId }
        val changed =
            remote
                .map { summary -> summary.row(hostId, local[summary.sessionId]) }
                .filter { row -> row != local[row.sessionId] }
        if (changed.isNotEmpty()) sessions.upsert(changed)
        // The engine answers with the whole directory, so a locally mirrored session it no longer
        // lists was deleted there; keep the local mirror from resurrecting it in the offline list.
        val vanished = local.keys - remote.map { it.sessionId }.toSet()
        if (vanished.isNotEmpty()) sessions.deleteAll(hostId, vanished.toList())
        val now = clock()
        cursors.put(SyncCursorRow(hostId, DIRECTORY_STREAM, now.toString(), now))
        return SyncReport(changed.size, remote.size, vanished.toList())
    }
}

private fun SessionSummary.row(hostId: String, local: SessionRow?): SessionRow =
    SessionRow(
        hostId = hostId,
        sessionId = sessionId,
        title = title.orEmpty(),
        updatedAt = updatedAt,
        running = running,
        remoteRev = local?.remoteRev,
        syncState = local?.syncState ?: SyncStates.ACKED,
    )

package dev.fughetta.dshcompose.data.sync

import android.content.Context
import dev.fughetta.dshcompose.data.database.AppDatabase
import dev.fughetta.dshcompose.data.database.OutboxRow
import dev.fughetta.dshcompose.data.database.TranscriptItemRow
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.protocol.ProtocolClient
import dev.fughetta.dshcompose.protocol.SessionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Coalesced transcript batches written by the reader. */
typealias TranscriptMirrorSink = suspend (String, String, List<SessionRecord>) -> Unit

/** Local directory reads and queued writes share one mirror owner. */
interface SessionCache {
    suspend fun renameLocal(hostId: String, sessionId: String, title: String, at: Long)

    suspend fun enqueueRename(hostId: String, sessionId: String, title: String, receiptId: String)

    suspend fun drainWrites(hostId: String, deliver: suspend (OutboxRow) -> Delivery): DrainReport

    /** Sessions whose queued rename still has no final answer. */
    suspend fun outstandingRenameSessions(hostId: String): Set<String>

    /** Clears the pending marker once the host answered the write, accepted or rejected. */
    suspend fun markSynced(hostId: String, sessionId: String)

    /** Re-queues writes whose previous attempt ended without an answer. */
    suspend fun retryUnknown(hostId: String): Int

    fun watch(hostId: String): Flow<List<SessionSummary>>

    suspend fun cached(hostId: String): List<SessionSummary>

    /** Persists the directory a connection just read; only changed rows are written. */
    suspend fun store(hostId: String, rows: List<SessionSummary>): SyncReport
}

/**
 * Stores host replies for offline directory and transcript reads. A failed mirror write must never
 * fail the connection: [sync] is best-effort and reports only what it persisted.
 */
class SessionMirror(
    private val catalog: LocalSessionCatalog,
    private val transcripts: TranscriptSync,
    private val outbox: OutboxDrain,
) : SessionCache {
    override fun watch(hostId: String): Flow<List<SessionSummary>> = catalog.watch(hostId)

    override suspend fun cached(hostId: String): List<SessionSummary> = catalog.cached(hostId)

    override suspend fun store(hostId: String, rows: List<SessionSummary>): SyncReport =
        syncDirectory(hostId) { rows }

    /**
     * Removes everything cached for a host that is being deleted. Queued writes and paging cursors
     * go too: the host is gone, so replaying them or resuming from a cursor would be meaningless.
     */
    suspend fun clearHost(hostId: String) {
        outbox.clear(hostId)
        transcripts.clear(hostId)
        catalog.clear(hostId)
    }

    override suspend fun outstandingRenameSessions(hostId: String): Set<String> =
        outbox.outstandingSessions(hostId, KIND_RENAME)

    override suspend fun markSynced(hostId: String, sessionId: String) =
        catalog.markSynced(hostId, sessionId)

    suspend fun syncDirectory(
        hostId: String,
        fetch: suspend () -> List<SessionSummary>,
    ): SyncReport {
        val report = catalog.refresh(hostId, fetch)
        // A session the host no longer lists is gone there; its mirrored transcript goes with it.
        transcripts.deleteSessions(hostId, report.removed)
        return report
    }

    suspend fun syncHost(
        hostId: String,
        connections: dev.fughetta.dshcompose.data.link.HostConnections,
    ): dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome {
        var acquired = false
        return try {
            val client =
                kotlinx.coroutines.withTimeout(30_000L) {
                    connections.acquire(hostId).also { acquired = true }
                }
            syncDirectory(hostId, client)
            dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome.Completed
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            if (connections.hasLink(hostId))
                dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome.Retryable
            else dev.fughetta.dshcompose.domain.sessions.HostSyncOutcome.Unavailable
        } finally {
            if (acquired) connections.release(hostId)
        }
    }

    suspend fun syncDirectory(hostId: String, client: ProtocolClient): SyncReport =
        syncDirectory(hostId) { client.listSessions() }

    suspend fun syncTranscript(
        hostId: String,
        sessionId: String,
        records: List<SessionRecord>,
    ): Int = transcripts.append(hostId, sessionId, records)

    suspend fun syncTranscript(
        hostId: String,
        sessionId: String,
        client: ProtocolClient,
        throughSeq: Long,
    ): Int = syncTranscript(hostId, sessionId, client.history(sessionId, throughSeq).records)

    suspend fun cachedTranscript(
        hostId: String,
        sessionId: String,
        limit: Int,
    ): List<TranscriptItemRow> = transcripts.cached(hostId, sessionId, limit)

    /** Older mirrored records in engine form, newest first, for offline scroll-back. */
    suspend fun cachedRecordsBefore(
        hostId: String,
        sessionId: String,
        beforeSeq: Long,
        limit: Int,
    ): List<SessionRecord> = transcripts.older(hostId, sessionId, beforeSeq, limit).map(::toRecord)

    /** Mirrored records in engine form, ready for the transcript fold. */
    suspend fun cachedRecords(hostId: String, sessionId: String, limit: Int): List<SessionRecord> =
        transcripts.cached(hostId, sessionId, limit).mapNotNull { row ->
            runCatching { toRecord(row) }.getOrNull()
        }

    private fun toRecord(row: TranscriptItemRow): SessionRecord =
        SessionRecord(
            row.seq,
            row.recordedAt,
            row.kind,
            Json.parseToJsonElement(row.payload).jsonObject,
        )

    /** Mirrors a rename locally and queues it for the next connection. */
    override suspend fun renameLocal(hostId: String, sessionId: String, title: String, at: Long) =
        catalog.renameLocal(hostId, sessionId, title, at)

    override suspend fun enqueueRename(
        hostId: String,
        sessionId: String,
        title: String,
        receiptId: String,
    ) {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        enqueueWrite(hostId, sessionId, KIND_RENAME, payload.toString(), receiptId)
    }

    override suspend fun drainWrites(
        hostId: String,
        deliver: suspend (OutboxRow) -> Delivery,
    ): DrainReport = outbox.drain(hostId, listOf(KIND_RENAME), deliver)

    override suspend fun retryUnknown(hostId: String): Int =
        outbox.retryUnknown(hostId, listOf(KIND_RENAME))

    suspend fun enqueueWrite(
        hostId: String,
        sessionId: String?,
        kind: String,
        payload: String,
        receiptId: String,
    ): Long = outbox.enqueue(hostId, sessionId, kind, payload, receiptId)

    companion object {
        /** Outbox kind for a session rename waiting for a connection. */
        const val KIND_RENAME = "session.rename"
    }
}

/** Builds the mirror over the app database; callers pass one connection's host id and client. */
fun createSessionMirror(context: Context): SessionMirror {
    val database = AppDatabase.get(context.applicationContext)
    return SessionMirror(
        catalog =
            LocalSessionCatalog(
                database.sessionMirror(),
                SessionDirectorySync(database.sessionMirror(), database.syncCursors()),
            ),
        transcripts = TranscriptSync(database.transcriptMirror(), database.syncCursors()),
        outbox = OutboxDrain(database.outbox()),
    )
}

package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.SyncCursorDao
import dev.fughetta.dshcompose.data.database.SyncCursorRow
import dev.fughetta.dshcompose.data.database.TranscriptItemRow
import dev.fughetta.dshcompose.data.database.TranscriptMirrorDao
import dev.fughetta.dshcompose.protocol.SessionRecord

/** Cursor stream prefix of one session transcript. */
const val TRANSCRIPT_STREAM_PREFIX = "session:"

/**
 * Records kept per session in the local mirror. Deeper offline history is intentionally dropped:
 * the mirror must not grow without bound, and older pages are still readable from the host when
 * connected.
 */
const val TRANSCRIPT_KEEP = 5_000

/**
 * Mirrors engine session records into `transcript_items` and keeps one cursor per session. Records
 * are stored as the engine emitted them (`seq`/`type`/`data`) so a re-read is idempotent by `seq`
 * and the folding that the UI expects still happens when the transcript is read.
 */
class TranscriptSync(
    private val transcripts: TranscriptMirrorDao,
    private val cursors: SyncCursorDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keep: Int = TRANSCRIPT_KEEP,
) {
    fun stream(sessionId: String) = "$TRANSCRIPT_STREAM_PREFIX$sessionId"

    /** Last seq this host mirrored for the session, or null before the first read. */
    suspend fun cursor(hostId: String, sessionId: String): Long? =
        cursors.read(hostId, stream(sessionId))?.toLongOrNull()

    /**
     * Upserts the records and moves the cursor to the newest seq; re-reading the same page is free.
     */
    suspend fun append(hostId: String, sessionId: String, records: List<SessionRecord>): Int {
        if (records.isEmpty()) return 0
        transcripts.upsert(
            records.map {
                TranscriptItemRow(
                    hostId = hostId,
                    sessionId = sessionId,
                    seq = it.seq,
                    kind = it.type,
                    payload = it.data.toString(),
                    recordedAt = it.time,
                )
            },
        )
        // Batches may arrive out of order when an older page is re-read; the cursor never moves
        // backwards.
        val last = records.maxOf { it.seq }
        val current = cursors.read(hostId, stream(sessionId))?.toLongOrNull()
        if (current == null || last > current)
            cursors.put(SyncCursorRow(hostId, stream(sessionId), last.toString(), clock()))
        prune(hostId, sessionId)
        return records.size
    }

    /** Keeps only the newest [keep] records so a long session cannot grow the mirror forever. */
    private suspend fun prune(hostId: String, sessionId: String) {
        val boundary = transcripts.seqAt(hostId, sessionId, keep - 1) ?: return
        transcripts.deleteBefore(hostId, sessionId, boundary)
    }

    /** Drops the mirrored pages of sessions that no longer exist on the host. */
    suspend fun deleteSessions(hostId: String, sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        transcripts.deleteAll(hostId, sessionIds)
        for (sessionId in sessionIds) cursors.clearStream(hostId, stream(sessionId))
    }

    /** Drops this host's mirrored transcript pages and their cursors. */
    suspend fun clear(hostId: String) {
        transcripts.clear(hostId)
        cursors.clear(hostId)
    }

    /** Mirrored records older than [beforeSeq], newest first, for backward paging. */
    suspend fun older(
        hostId: String,
        sessionId: String,
        beforeSeq: Long,
        limit: Int,
    ): List<TranscriptItemRow> = transcripts.older(hostId, sessionId, beforeSeq, limit)

    /** Newest [limit] mirrored records in ascending seq order. */
    suspend fun cached(hostId: String, sessionId: String, limit: Int): List<TranscriptItemRow> =
        transcripts.page(hostId, sessionId, Long.MAX_VALUE, limit).asReversed()
}

package dev.fughetta.dshcompose.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local-first mirror of an engine session directory entry. The engine stays authoritative for
 * session data; `remoteRev` records the revision the row was folded from and `syncState` the
 * pending/local state (see [SyncStates]).
 */
@Entity(
    tableName = "sessions",
    primaryKeys = ["hostId", "sessionId"],
    indices = [Index(value = ["hostId", "updatedAt"])],
)
data class SessionRow(
    val hostId: String,
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val running: Boolean,
    val remoteRev: String?,
    val syncState: String,
)

/** One folded engine event, appended by `seq` so a cursor can resume mid-stream. */
@Entity(tableName = "transcript_items", primaryKeys = ["hostId", "sessionId", "seq"])
data class TranscriptItemRow(
    val hostId: String,
    val sessionId: String,
    val seq: Long,
    val kind: String,
    val payload: String,
    val recordedAt: Long,
)

/**
 * A write that has not been acknowledged by the host yet. Dangerous operations only delete local
 * state after `acked`, so [receiptId] lets a retry after reconnect be de-duplicated by the engine.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["hostId", "state"])])
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val hostId: String,
    val sessionId: String?,
    val kind: String,
    val payload: String,
    val receiptId: String,
    val state: String,
    val createdAt: Long,
    val attempts: Int = 0,
)

/** Incremental read position for one host stream (session directory or one session transcript). */
@Entity(tableName = "sync_cursors", primaryKeys = ["hostId", "stream"])
data class SyncCursorRow(
    val hostId: String,
    val stream: String,
    val cursor: String,
    val updatedAt: Long,
)

/** Values stored in [OutboxRow.state]; a retry keeps `receiptId` stable across reconnects. */
object SyncStates {
    /** Local row written, nothing sent yet. */
    const val PENDING = "pending"

    /** Handed to the engine, no answer yet. */
    const val SENDING = "sending"

    /** Host confirmed the write; the local row now reflects the confirmed value. */
    const val ACKED = "acked"

    /** Host rejected the write with a stable error code. Retried only on explicit user action. */
    const val FAILED = "failed"

    /** Sent, but the connection dropped before an answer; retry with the same receipt. */
    const val UNKNOWN = "unknown"

    /** States that the sync engine still owns. */
    val outstanding = setOf(PENDING, SENDING, UNKNOWN)
}

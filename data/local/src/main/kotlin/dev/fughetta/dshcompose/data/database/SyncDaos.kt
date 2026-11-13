package dev.fughetta.dshcompose.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Reads the mirrored directory; UI layers subscribe to [watch] instead of the connection. */
@Dao
interface SessionMirrorDao {
    @Upsert suspend fun upsert(rows: List<SessionRow>)

    @Query("SELECT * FROM sessions WHERE hostId=:hostId ORDER BY updatedAt DESC, sessionId DESC")
    fun watch(hostId: String): Flow<List<SessionRow>>

    @Query("SELECT * FROM sessions WHERE hostId=:hostId ORDER BY updatedAt DESC, sessionId DESC")
    suspend fun list(hostId: String): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE hostId=:hostId AND sessionId=:sessionId")
    suspend fun find(hostId: String, sessionId: String): SessionRow?

    @Query("UPDATE sessions SET syncState=:state WHERE hostId=:hostId AND sessionId=:sessionId")
    suspend fun setState(hostId: String, sessionId: String, state: String)

    @Query(
        "UPDATE sessions SET title=:title, syncState=:state, updatedAt=:updatedAt WHERE hostId=:hostId AND sessionId=:sessionId",
    )
    suspend fun rename(
        hostId: String,
        sessionId: String,
        title: String,
        state: String,
        updatedAt: Long,
    )

    @Query("DELETE FROM sessions WHERE hostId=:hostId AND sessionId=:sessionId")
    suspend fun delete(hostId: String, sessionId: String)

    @Query("DELETE FROM sessions WHERE hostId=:hostId AND sessionId IN (:sessionIds)")
    suspend fun deleteAll(hostId: String, sessionIds: List<String>)

    @Query("DELETE FROM sessions WHERE hostId=:hostId") suspend fun clear(hostId: String)
}

/** Appends folded transcript items by `seq`; a session is read from its persisted tail. */
@Dao
interface TranscriptMirrorDao {
    @Upsert suspend fun upsert(rows: List<TranscriptItemRow>)

    @Query(
        "SELECT * FROM transcript_items WHERE hostId=:hostId AND sessionId=:sessionId AND seq<=:throughSeq ORDER BY seq DESC LIMIT :limit",
    )
    suspend fun page(
        hostId: String,
        sessionId: String,
        throughSeq: Long,
        limit: Int,
    ): List<TranscriptItemRow>

    @Query(
        "SELECT * FROM transcript_items WHERE hostId=:hostId AND sessionId=:sessionId AND seq<:beforeSeq ORDER BY seq DESC LIMIT :limit",
    )
    suspend fun older(
        hostId: String,
        sessionId: String,
        beforeSeq: Long,
        limit: Int,
    ): List<TranscriptItemRow>

    @Query("SELECT MAX(seq) FROM transcript_items WHERE hostId=:hostId AND sessionId=:sessionId")
    suspend fun lastSeq(hostId: String, sessionId: String): Long?

    @Query(
        "SELECT seq FROM transcript_items WHERE hostId=:hostId AND sessionId=:sessionId ORDER BY seq DESC LIMIT 1 OFFSET :offset",
    )
    suspend fun seqAt(hostId: String, sessionId: String, offset: Int): Long?

    @Query("DELETE FROM transcript_items WHERE hostId=:hostId AND sessionId=:sessionId")
    suspend fun delete(hostId: String, sessionId: String)

    @Query(
        "DELETE FROM transcript_items WHERE hostId=:hostId AND sessionId=:sessionId AND seq < :beforeSeq",
    )
    suspend fun deleteBefore(hostId: String, sessionId: String, beforeSeq: Long)

    @Query("DELETE FROM transcript_items WHERE hostId=:hostId AND sessionId IN (:sessionIds)")
    suspend fun deleteAll(hostId: String, sessionIds: List<String>)

    @Query("DELETE FROM transcript_items WHERE hostId=:hostId") suspend fun clear(hostId: String)
}

/** Drains one host's writes in insertion order; the engine de-duplicates repeated receipts. */
@Dao
interface OutboxDao {
    @Insert suspend fun enqueue(row: OutboxRow): Long

    @Query(
        "SELECT * FROM outbox WHERE hostId=:hostId AND kind IN (:kinds) AND state IN (:states) ORDER BY localId LIMIT :limit",
    )
    suspend fun next(
        hostId: String,
        kinds: List<String>,
        states: List<String>,
        limit: Int,
    ): List<OutboxRow>

    /** Takes a pending row for this attempt; the counter feeds the retry/backoff policy. */
    @Query("UPDATE outbox SET state=:state, attempts=attempts+1 WHERE localId=:localId")
    suspend fun claim(localId: Long, state: String)

    /** Records a terminal answer (`unknown`/`failed`) without counting a new attempt. */
    @Query("UPDATE outbox SET state=:state WHERE localId=:localId")
    suspend fun settle(localId: Long, state: String)

    @Query("UPDATE outbox SET state=:to WHERE hostId=:hostId AND kind IN (:kinds) AND state=:from")
    suspend fun retag(hostId: String, kinds: List<String>, from: String, to: String): Int

    @Query("SELECT * FROM outbox WHERE hostId=:hostId AND state=:state ORDER BY localId")
    suspend fun byState(hostId: String, state: String): List<OutboxRow>

    @Query("SELECT * FROM outbox WHERE hostId=:hostId AND kind=:kind ORDER BY localId")
    suspend fun byKind(hostId: String, kind: String): List<OutboxRow>

    @Query("UPDATE outbox SET state=:state WHERE hostId=:hostId AND receiptId=:receiptId")
    suspend fun setStateByReceipt(hostId: String, receiptId: String, state: String)

    @Query("DELETE FROM outbox WHERE hostId=:hostId AND receiptId=:receiptId")
    suspend fun removeByReceipt(hostId: String, receiptId: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE hostId=:hostId")
    suspend fun count(hostId: String): Int

    @Query("DELETE FROM outbox WHERE localId=:localId") suspend fun remove(localId: Long)

    @Query("DELETE FROM outbox WHERE hostId=:hostId") suspend fun clear(hostId: String)
}

/** Per-stream read position, written after the folded items of that batch are stored. */
@Dao
interface SyncCursorDao {
    @Query("SELECT cursor FROM sync_cursors WHERE hostId=:hostId AND stream=:stream")
    suspend fun read(hostId: String, stream: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: SyncCursorRow)

    @Query("DELETE FROM sync_cursors WHERE hostId=:hostId") suspend fun clear(hostId: String)

    @Query("DELETE FROM sync_cursors WHERE hostId=:hostId AND stream=:stream")
    suspend fun clearStream(hostId: String, stream: String)
}

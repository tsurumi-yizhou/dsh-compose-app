package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.OutboxDao
import dev.fughetta.dshcompose.data.database.OutboxRow
import dev.fughetta.dshcompose.data.database.SessionMirrorDao
import dev.fughetta.dshcompose.data.database.SessionRow
import dev.fughetta.dshcompose.data.database.SyncCursorDao
import dev.fughetta.dshcompose.data.database.SyncCursorRow
import dev.fughetta.dshcompose.data.database.TranscriptItemRow
import dev.fughetta.dshcompose.data.database.TranscriptMirrorDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory DAO doubles shared by the sync tests. */
internal class FakeSessionMirror : SessionMirrorDao {
    val storage = linkedMapOf<String, SessionRow>()
    var writes = 0
    private val revisions = MutableStateFlow(0)

    private fun publish() {
        revisions.value += 1
    }

    override suspend fun upsert(rows: List<SessionRow>) {
        writes += rows.size
        for (row in rows) storage["${row.hostId}/${row.sessionId}"] = row
        publish()
    }

    override fun watch(hostId: String): Flow<List<SessionRow>> = revisions.map { rows(hostId) }

    private fun rows(hostId: String): List<SessionRow> =
        storage.values
            .filter { it.hostId == hostId }
            .sortedWith(
                compareByDescending<SessionRow> { it.updatedAt }.thenByDescending { it.sessionId },
            )

    override suspend fun list(hostId: String): List<SessionRow> = rows(hostId)

    override suspend fun find(hostId: String, sessionId: String): SessionRow? =
        storage["$hostId/$sessionId"]

    override suspend fun deleteAll(hostId: String, sessionIds: List<String>) {
        sessionIds.forEach { storage.remove("$hostId/$it") }
        publish()
    }

    override suspend fun rename(
        hostId: String,
        sessionId: String,
        title: String,
        state: String,
        updatedAt: Long,
    ) {
        val key = "$hostId/$sessionId"
        val row = storage[key] ?: return
        storage[key] = row.copy(title = title, syncState = state, updatedAt = updatedAt)
        publish()
    }

    override suspend fun setState(hostId: String, sessionId: String, state: String) {
        storage["$hostId/$sessionId"]?.let {
            storage["$hostId/$sessionId"] = it.copy(syncState = state)
        }
        publish()
    }

    override suspend fun delete(hostId: String, sessionId: String) {
        storage.remove("$hostId/$sessionId")
        publish()
    }

    override suspend fun clear(hostId: String) {
        storage.keys.filter { it.startsWith("$hostId/") }.forEach(storage::remove)
        publish()
    }
}

internal class FakeCursors : SyncCursorDao {
    val storage = linkedMapOf<String, SyncCursorRow>()

    override suspend fun read(hostId: String, stream: String): String? =
        storage["$hostId/$stream"]?.cursor

    override suspend fun put(row: SyncCursorRow) {
        storage["${row.hostId}/${row.stream}"] = row
    }

    override suspend fun clearStream(hostId: String, stream: String) {
        storage.remove("$hostId/$stream")
    }

    override suspend fun clear(hostId: String) {
        storage.keys.filter { it.startsWith("$hostId/") }.forEach(storage::remove)
    }
}

internal class FakeOutbox : OutboxDao {
    val storage = mutableListOf<OutboxRow>()
    private var nextId = 1L

    override suspend fun enqueue(row: OutboxRow): Long {
        val id = nextId++
        storage += row.copy(localId = id)
        return id
    }

    override suspend fun next(
        hostId: String,
        kinds: List<String>,
        states: List<String>,
        limit: Int,
    ): List<OutboxRow> =
        storage
            .filter { it.hostId == hostId && it.kind in kinds && it.state in states }
            .sortedBy { it.localId }
            .take(limit)

    override suspend fun claim(localId: Long, state: String) {
        update(localId) { it.copy(state = state, attempts = it.attempts + 1) }
    }

    override suspend fun settle(localId: Long, state: String) {
        update(localId) { it.copy(state = state) }
    }

    override suspend fun retag(hostId: String, kinds: List<String>, from: String, to: String): Int {
        var changed = 0
        for (index in storage.indices) {
            val row = storage[index]
            if (row.hostId == hostId && row.kind in kinds && row.state == from) {
                storage[index] = row.copy(state = to)
                changed++
            }
        }
        return changed
    }

    override suspend fun byState(hostId: String, state: String): List<OutboxRow> = storage.filter {
        it.hostId == hostId && it.state == state
    }

    override suspend fun byKind(hostId: String, kind: String): List<OutboxRow> = storage.filter {
        it.hostId == hostId && it.kind == kind
    }

    override suspend fun setStateByReceipt(hostId: String, receiptId: String, state: String) {
        for (index in storage.indices) {
            val row = storage[index]
            if (row.hostId == hostId && row.receiptId == receiptId)
                storage[index] = row.copy(state = state)
        }
    }

    override suspend fun removeByReceipt(hostId: String, receiptId: String) {
        storage.removeAll { it.hostId == hostId && it.receiptId == receiptId }
    }

    override suspend fun count(hostId: String): Int = storage.count { it.hostId == hostId }

    override suspend fun remove(localId: Long) {
        storage.removeAll { it.localId == localId }
    }

    override suspend fun clear(hostId: String) {
        storage.removeAll { it.hostId == hostId }
    }

    private fun update(localId: Long, transform: (OutboxRow) -> OutboxRow) {
        val index = storage.indexOfFirst { it.localId == localId }
        if (index >= 0) storage[index] = transform(storage[index])
    }
}

internal class FakeTranscripts : TranscriptMirrorDao {
    val storage = linkedMapOf<String, TranscriptItemRow>()

    override suspend fun upsert(rows: List<TranscriptItemRow>) {
        for (row in rows) storage["${row.hostId}/${row.sessionId}/${row.seq}"] = row
    }

    override suspend fun deleteAll(hostId: String, sessionIds: List<String>) {
        storage.keys
            .filter { it.startsWith("$hostId/") && it.split('/')[1] in sessionIds }
            .forEach(storage::remove)
    }

    override suspend fun seqAt(hostId: String, sessionId: String, offset: Int): Long? =
        storage.values
            .filter { it.hostId == hostId && it.sessionId == sessionId }
            .sortedByDescending { it.seq }
            .getOrNull(offset)
            ?.seq

    override suspend fun deleteBefore(hostId: String, sessionId: String, beforeSeq: Long) {
        storage.keys
            .filter { key ->
                val row = storage[key]!!
                row.hostId == hostId && row.sessionId == sessionId && row.seq < beforeSeq
            }
            .forEach(storage::remove)
    }

    override suspend fun page(
        hostId: String,
        sessionId: String,
        throughSeq: Long,
        limit: Int,
    ): List<TranscriptItemRow> =
        storage.values
            .filter { it.hostId == hostId && it.sessionId == sessionId && it.seq <= throughSeq }
            .sortedByDescending { it.seq }
            .take(limit)

    override suspend fun older(
        hostId: String,
        sessionId: String,
        beforeSeq: Long,
        limit: Int,
    ): List<TranscriptItemRow> =
        storage.values
            .filter { it.hostId == hostId && it.sessionId == sessionId && it.seq < beforeSeq }
            .sortedByDescending { it.seq }
            .take(limit)

    override suspend fun lastSeq(hostId: String, sessionId: String): Long? =
        storage.values
            .filter { it.hostId == hostId && it.sessionId == sessionId }
            .maxOfOrNull { it.seq }

    override suspend fun delete(hostId: String, sessionId: String) {
        storage.keys.filter { it.startsWith("$hostId/$sessionId/") }.forEach(storage::remove)
    }

    override suspend fun clear(hostId: String) {
        storage.keys.filter { it.startsWith("$hostId/") }.forEach(storage::remove)
    }
}

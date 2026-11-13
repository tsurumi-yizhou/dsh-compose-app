package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.OutboxDao
import dev.fughetta.dshcompose.data.database.OutboxRow
import dev.fughetta.dshcompose.data.database.SyncStates

/** The host's answer to one outbox write. */
sealed interface Delivery {
    /** The engine applied the write; the local optimistic value is now the confirmed one. */
    data object Acknowledged : Delivery

    /** The engine rejected the write with a stable code; the row stays for the user to decide. */
    data class Rejected(val code: String) : Delivery

    /** The connection dropped before an answer; a reconnect retries with the same receipt. */
    data object Unknown : Delivery
}

data class DrainReport(
    val acknowledged: Int,
    val rejected: Int,
    val unknown: Int,
    /** Sessions whose write got a final answer, acknowledged or rejected. */
    val settledSessions: List<String> = emptyList(),
)

/**
 * Owns the write half of the local-first model: rows go into the outbox before anything is sent and
 * are drained per host in insertion order, one answer at a time. An `Unknown` answer leaves the row
 * in the outbox so the next connection can retry it with the same receipt id, which is what lets
 * the engine de-duplicate.
 */
class OutboxDrain(
    private val outbox: OutboxDao,
    private val batchSize: Int = 8,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(
        hostId: String,
        sessionId: String?,
        kind: String,
        payload: String,
        receiptId: String,
    ): Long =
        outbox.enqueue(
            OutboxRow(
                hostId = hostId,
                sessionId = sessionId,
                kind = kind,
                payload = payload,
                receiptId = receiptId,
                state = SyncStates.PENDING,
                createdAt = clock(),
            ),
        )

    /** Drops this host's queued writes; only a removed host loses them. */
    suspend fun clear(hostId: String) {
        outbox.clear(hostId)
    }

    /** Sessions of [kind] that still have an unanswered write in the outbox. */
    suspend fun outstandingSessions(hostId: String, kind: String): Set<String> =
        outbox
            .byKind(hostId, kind)
            .filter { it.state in SyncStates.outstanding }
            .mapNotNull { it.sessionId }
            .toSet()

    /**
     * Re-queues writes left in flight by an earlier connection: an unanswered delivery is
     * `UNKNOWN`, and one interrupted mid-send (process death, dropped socket) is still `SENDING`.
     * Both retry with the same receipt so the engine can de-duplicate.
     */
    suspend fun retryUnknown(hostId: String, kinds: List<String>): Int =
        outbox.retag(hostId, kinds, SyncStates.UNKNOWN, SyncStates.PENDING) +
            outbox.retag(hostId, kinds, SyncStates.SENDING, SyncStates.PENDING)

    suspend fun drain(
        hostId: String,
        kinds: List<String>,
        deliver: suspend (OutboxRow) -> Delivery,
    ): DrainReport {
        var acknowledged = 0
        var rejected = 0
        var unknown = 0
        val settled = mutableListOf<String>()
        while (true) {
            val batch = outbox.next(hostId, kinds, listOf(SyncStates.PENDING), batchSize)
            if (batch.isEmpty()) break
            for (row in batch) {
                outbox.claim(row.localId, SyncStates.SENDING)
                val answer =
                    try {
                        deliver(row)
                    } catch (error: Throwable) {
                        // Keep the row retryable instead of leaving it claimed forever.
                        outbox.settle(row.localId, SyncStates.UNKNOWN)
                        throw error
                    }
                when (answer) {
                    Delivery.Acknowledged -> {
                        outbox.remove(row.localId)
                        row.sessionId?.let(settled::add)
                        acknowledged++
                    }
                    is Delivery.Rejected -> {
                        outbox.settle(row.localId, SyncStates.FAILED)
                        row.sessionId?.let(settled::add)
                        rejected++
                    }
                    Delivery.Unknown -> {
                        outbox.settle(row.localId, SyncStates.UNKNOWN)
                        unknown++
                    }
                }
            }
        }
        return DrainReport(acknowledged, rejected, unknown, settled)
    }
}

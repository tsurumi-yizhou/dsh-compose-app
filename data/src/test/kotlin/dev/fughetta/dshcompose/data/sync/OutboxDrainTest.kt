package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.data.database.SyncStates
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OutboxDrainTest {
    private val host = "host"
    private val kinds = listOf("send", "delete", "permission", "rename")

    @Test
    fun drainDeliversInInsertionOrderAndRemovesAcknowledgedRows() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox) { 1_000L }
        drain.enqueue(host, "s", "send", "{}", "r1")
        drain.enqueue(host, "s", "delete", "{}", "r2")
        val delivered = mutableListOf<String>()

        val report =
            drain.drain(host, kinds) { row ->
                delivered += row.kind
                Delivery.Acknowledged
            }

        assertEquals(listOf("send", "delete"), delivered)
        assertEquals(
            DrainReport(
                acknowledged = 2,
                rejected = 0,
                unknown = 0,
                settledSessions = listOf("s", "s"),
            ),
            report,
        )
        assertEquals(0, outbox.count(host))
        assertEquals(0, outbox.storage.size)
    }

    @Test
    fun rejectedAndUnknownWritesStayPendingForTheNextRound() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox) { 1_000L }
        drain.enqueue(host, "s", "permission", "{}", "r1")
        drain.enqueue(host, "s", "send", "{}", "r2")

        val report =
            drain.drain(host, kinds) { row ->
                if (row.kind == "permission") Delivery.Rejected("PERMISSION_DENIED")
                else Delivery.Unknown
            }

        assertEquals(
            DrainReport(acknowledged = 0, rejected = 1, unknown = 1, settledSessions = listOf("s")),
            report,
        )
        assertEquals(listOf("r1"), outbox.byState(host, SyncStates.FAILED).map { it.receiptId })
        val unknown = outbox.byState(host, SyncStates.UNKNOWN).single()
        assertEquals("r2", unknown.receiptId)
        assertEquals(1, unknown.attempts)
    }

    @Test
    fun retryUnknownRequeuesTheSameReceiptForTheNextConnection() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox) { 1_000L }
        drain.enqueue(host, "s", "send", "{}", "r1")
        drain.drain(host, kinds) { Delivery.Unknown }

        assertEquals(1, drain.retryUnknown(host, kinds))
        assertEquals(listOf(SyncStates.PENDING), outbox.storage.map { it.state })

        val receipts = mutableListOf<String>()
        drain.drain(host, kinds) { row ->
            receipts += row.receiptId
            Delivery.Acknowledged
        }
        assertEquals(listOf("r1"), receipts)
        assertEquals(0, outbox.storage.size)
    }

    @Test
    fun drainContinuesAcrossBatchesUntilTheHostQueueIsEmpty() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox, clock = { 1_000L }, batchSize = 1)
        repeat(3) { index -> drain.enqueue(host, "s", "send", "{}", "r$index") }
        val delivered = mutableListOf<String>()

        val report =
            drain.drain(host, kinds) { row ->
                delivered += row.receiptId
                Delivery.Acknowledged
            }

        assertEquals(listOf("r0", "r1", "r2"), delivered)
        assertEquals(3, report.acknowledged)
    }

    @Test
    fun anotherHostsQueueIsNotDrained() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox) { 1_000L }
        drain.enqueue("other", "s", "send", "{}", "r1")

        drain.drain(host, kinds) { Delivery.Acknowledged }

        assertEquals(1, outbox.count("other"))
    }

    @Test
    fun aSendInterruptedMidFlightStaysRetryableWithTheSameReceipt() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox) { 1_000L }
        drain.enqueue(host, "s", "send", "{}", "r1")

        val thrown = runCatching {
            drain.drain(host, kinds) { throw IllegalStateException("socket closed") }
        }
        assertTrue(thrown.isFailure)
        assertEquals(listOf(SyncStates.UNKNOWN), outbox.storage.map { it.state })

        assertEquals(1, drain.retryUnknown(host, kinds))
        val receipts = mutableListOf<String>()
        drain.drain(host, kinds) { row ->
            receipts += row.receiptId
            Delivery.Acknowledged
        }
        assertEquals(listOf("r1"), receipts)
        assertEquals(0, outbox.storage.size)
    }

    @Test
    fun retryUnknownAlsoRequeuesRowsLeftClaimedByADeadProcess() = runTest {
        val outbox = FakeOutbox()
        val drain = OutboxDrain(outbox) { 1_000L }
        drain.enqueue(host, "s", "rename", "{}", "r1")
        outbox.claim(outbox.storage.single().localId, SyncStates.SENDING)

        assertEquals(1, drain.retryUnknown(host, kinds))
        assertEquals(listOf(SyncStates.PENDING), outbox.storage.map { it.state })
    }
}

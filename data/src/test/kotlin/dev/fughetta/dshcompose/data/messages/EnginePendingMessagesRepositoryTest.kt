package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.messages.PendingPromptsOwner
import dev.fughetta.dshcompose.protocol.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineMessagesRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private class CleanupQueue : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private val cleanup = CleanupQueue()
    private val scope = CoroutineScope(SupervisorJob() + cleanup)
    private val ledger = EngineMessagesRepository(scope, cleanup)
    private val owner = PendingPromptsOwner("local", "/root/.dsh", "session-a")

    @Test
    fun aRestoredPromptIsAdmittedOnceAndBlankOnesAreIgnored() {
        val pending =
            ledger.begin(
                host = owner.host,
                home = owner.home,
                sessionId = owner.sessionId,
                text = "hello",
                composerText = "hello",
                quote = null,
                files = emptyList(),
            )
        val fresh = EngineMessagesRepository(scope, cleanup)
        try {
            assertTrue(fresh.restore(pending.copy(state = PendingPromptState.Uncertain)))
            assertFalse(fresh.restore(pending))
            assertEquals("hello", fresh.find(owner, pending.requestId)?.text)
            assertEquals(1, fresh.state.value.size)
            assertFalse(fresh.restore(pending.copy(requestId = "blank", text = " ")))
        } finally {
            fresh.close()
        }
    }

    @After
    fun close() {
        ledger.close()
        scope.cancel()
    }

    private fun spool(): ComposerFile {
        val file = temporary.newFile().apply { writeText("attachment") }
        return ComposerFile(
            "attachment-a",
            "notes.txt",
            "text/plain",
            (file).absolutePath,
            file.length(),
        )
    }

    private fun begin(files: List<ComposerFile> = emptyList()) =
        ledger.begin(
            owner.host,
            owner.home,
            owner.sessionId,
            "final payload",
            "composer text",
            "quoted text",
            files,
            mode = PromptMode.Steer,
            timeZone = "Asia/Taipei",
            fileReceiptIds = listOf("receipt-a"),
        )

    private fun echo(prompt: PendingPrompt) =
        SessionRecord(
            1,
            1,
            "user/message",
            buildJsonObject {
                putJsonObject("source") {
                    put("kind", "user")
                    put("rpcId", prompt.requestId)
                }
            },
        )

    private fun inbox(prompt: PendingPrompt) =
        InboxItem(
            "queue-a",
            InboxSlot.NextTurn,
            prompt.text,
            prompt.requestId,
            "user",
            null,
            true,
        )

    @Test
    fun unknownOutcomeRequiresExplicitRetryAndPreservesOriginalRequest() {
        val first = begin()
        ledger.failed(first.token, HostTransportException())
        assertEquals(PendingPromptState.Uncertain, ledger.state.value[first.requestId]?.state)
        val rejected = assertThrows(OperationFailure::class.java) { begin() }
        assertEquals("message/outcome-unknown", rejected.code)
        val retry = ledger.retry(owner, first.requestId)
        assertEquals(first.requestId, retry.requestId)
        assertEquals(first.text, retry.text)
        assertEquals(first.mode, retry.mode)
        assertEquals(first.timeZone, retry.timeZone)
        assertEquals(first.fileReceiptIds, retry.fileReceiptIds)
        assertEquals(PendingPromptFold.Stale, ledger.failed(first.token, HostTransportException()))
        assertEquals(PendingPromptState.Sending, ledger.state.value[first.requestId]?.state)
        assertEquals(PendingPromptFold.Retained, ledger.acknowledged(owner, retry.requestId))
        assertNull(ledger.state.value[first.requestId])
        assertFalse(ledger.wasConsumed(owner, first.requestId))
        assertEquals(
            "message/already-accepted",
            assertThrows(OperationFailure::class.java) { ledger.retry(owner, first.requestId) }
                .code,
        )
    }

    @Test
    fun durableUserEchoBeforeAckRemainsDeliveryProof() {
        val file = spool()
        val pending = begin(listOf(file))
        ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(pending))
        assertNull(ledger.state.value[pending.requestId])
        assertTrue(ledger.wasConsumed(owner, pending.requestId))
        assertEquals(PendingPromptFold.Consumed, ledger.acknowledged(owner, pending.requestId))
        assertEquals(
            PendingPromptFold.Consumed,
            ledger.failed(pending.token, HostTransportException()),
        )
        cleanup.drain()
        assertFalse(java.io.File(file.path).exists())
    }

    @Test
    fun anotherHostOrHomeCannotClaimQueueOrDelivery() {
        val pending = begin()
        val item = inbox(pending)
        ledger.observeInbox(owner.host, "/other-home", owner.sessionId, listOf(item))
        ledger.observeRecord("ssh-other", owner.home, owner.sessionId, echo(pending))
        ledger.observeRecord(owner.host, "/other-home", owner.sessionId, echo(pending))
        assertEquals(PendingPromptState.Sending, ledger.state.value[pending.requestId]?.state)
        assertNull(ledger.byItem(owner, item.id))
        assertFalse(ledger.wasConsumed(owner, pending.requestId))
        ledger.observeInbox(owner.host, owner.home, owner.sessionId, listOf(item))
        assertEquals(pending.requestId, ledger.byItem(owner, item.id)?.requestId)
        ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(pending))
        assertTrue(ledger.wasConsumed(owner, pending.requestId))
    }

    @Test
    fun delayedCleanupCannotDeleteSpoolTransferredBackAfterWithdrawal() {
        val file = spool()
        val first = begin(listOf(file))
        ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(first))
        // Hold the old IO deletion while a new request takes ownership of the same spool.
        val second = begin(listOf(file))
        ledger.acknowledged(owner, second.requestId)
        val item = inbox(second)
        ledger.observeInbox(owner.host, owner.home, owner.sessionId, listOf(item))
        val restored = ledger.withdrawn(owner, item.id, restore = true)
        assertEquals(second.requestId, restored?.requestId)
        assertEquals(listOf(file), restored?.files)
        assertFalse(ledger.wasConsumed(owner, second.requestId))
        assertEquals(PendingPromptFold.Withdrawn, ledger.acknowledged(owner, second.requestId))
        assertEquals(PendingPromptState.Recovered, ledger.find(owner, second.requestId)?.state)
        assertEquals(setOf(file.id), ledger.retainedFileIds())
        ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(second))
        cleanup.drain()
        assertTrue(java.io.File(file.path).exists())
        assertEquals(PendingPromptState.Recovered, ledger.find(owner, second.requestId)?.state)
        assertEquals(second.requestId, ledger.recoverDraft(owner, second.requestId)?.requestId)
        assertTrue(ledger.retainedFileIds().isEmpty())
        assertTrue(java.io.File(file.path).exists())
    }

    @Test
    fun submissionFailureRequiresRetryButLateDeliveryProofSettlesTheOriginalRequest() =
        runBlocking {
            val original = begin()
            val useCase = SubmitPendingPromptUseCase(ledger)
            val disconnected = java.io.IOException("disconnected")
            try {
                useCase(
                    original,
                    { PromptSubmissionResult.Failed(disconnected) },
                )
                fail("An unresolved send must report its transport failure")
            } catch (error: java.io.IOException) {
                assertSame(disconnected, error)
            }
            assertEquals(
                PendingPromptState.Uncertain,
                ledger.find(owner, original.requestId)?.state,
            )
            val retried = ledger.retry(owner, original.requestId)
            val accepted =
                useCase(
                    retried,
                    {
                        ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(retried))
                        PromptSubmissionResult.Failed(disconnected)
                    },
                )
            assertTrue(accepted)
            assertEquals(original.requestId, retried.requestId)
            assertTrue(ledger.wasConsumed(owner, original.requestId))
        }

    @Test
    fun cancellationStillPropagatesAfterDeliveryProofAndCannotReclaimTheSpool() = runBlocking {
        val attachment = spool()
        val pending = begin(listOf(attachment))
        val cancellation = CancellationException("retired connection")
        try {
            SubmitPendingPromptUseCase(ledger)(
                pending,
                {
                    ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(pending))
                    PromptSubmissionResult.Failed(cancellation)
                },
            )
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertNull(ledger.find(owner, pending.requestId))
        cleanup.drain()
        assertFalse(java.io.File(attachment.path).exists())
    }

    @Test
    fun confirmedDeletionReleasesPendingRecoveryOnlyForTheExactHostAndHome() {
        val attachment = spool()
        val first = begin(listOf(attachment))
        ledger.acknowledged(owner, first.requestId)
        val item = inbox(first)
        ledger.observeInbox(owner.host, owner.home, owner.sessionId, listOf(item))
        ledger.withdrawn(owner, item.id, restore = true)
        val otherFile = spool().copy(id = "other-attachment")
        val otherOwner = owner.copy(home = "/other-home")
        val other =
            ledger.begin(
                otherOwner.host,
                otherOwner.home,
                otherOwner.sessionId,
                "payload",
                "draft",
                null,
                listOf(otherFile),
            )
        ledger.sessionDeleted(owner)
        ledger.observeRecord(owner.host, owner.home, owner.sessionId, echo(first))
        cleanup.drain()
        assertFalse(java.io.File(attachment.path).exists())
        assertTrue(java.io.File(otherFile.path).exists())
        assertNull(ledger.find(owner, first.requestId))
        assertNotNull(ledger.find(otherOwner, other.requestId))
        assertEquals(PendingPromptFold.Withdrawn, ledger.acknowledged(owner, first.requestId))
    }

    @Test
    fun recoverySpoolRemainsInTheSharedPendingBudgetUntilTheComposerClaimsIt() {
        val attachment =
            spool()
                .copy(
                    bytes =
                        dev.fughetta.dshcompose.domain.attachments.AttachmentLimits.MAX_TOTAL_BYTES,
                )
        val first = begin(listOf(attachment))
        ledger.acknowledged(owner, first.requestId)
        val item = inbox(first)
        ledger.observeInbox(owner.host, owner.home, owner.sessionId, listOf(item))
        ledger.withdrawn(owner, item.id, restore = true)
        val nextFile = spool().copy(id = "next-attachment")
        try {
            begin(listOf(nextFile))
            fail("Recovered payload must count toward the pending budget")
        } catch (error: OperationFailure) {
            assertEquals("message/pending-storage-full", error.code)
        }
        ledger.recoverDraft(owner, first.requestId)
        assertEquals(listOf(nextFile), begin(listOf(nextFile)).files)
    }
}

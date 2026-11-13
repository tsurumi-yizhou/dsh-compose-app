package dev.fughetta.dshcompose.ui.sessions

import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDeletionViewModelTest {
    private class Operations(val remove: suspend (String) -> Unit) :
        dev.fughetta.dshcompose.testing.TestSessionsRepository() {
        override var active = true

        override suspend fun delete(sessionId: String) = remove(sessionId)

        override suspend fun createdAt(sessionId: String): Long? = error("unused")

        override suspend fun create(
            cwd: String?,
            workspaceId: String?,
            agentPreset: String?,
        ): String = error("unused")

        override suspend fun rename(sessionId: String, title: String): String = error("unused")

        override suspend fun forkSession(sessionId: String, atSeq: Long?): String = error("unused")

        override suspend fun forkState(
            sessionId: String,
        ): dev.fughetta.dshcompose.domain.sessions.SessionForkState? = error("unused")

        override suspend fun updateQueuedPrompt(
            sessionId: String,
            itemId: String,
            change: QueueChange,
        ): Unit = error("unused")
    }

    @Test
    fun bulkDeletionSharesOneConfirmationAndSeparatesAcknowledgementsFromCleanupFailure() =
        runTest {
            val first = CompletableDeferred<Unit>()
            val requested = mutableListOf<String>()
            val cleaned = mutableListOf<String>()
            val operations = Operations { id ->
                requested += id
                if (id == "ok") first.await()
                if (id == "running") throw SessionOperationException("session-running")
            }
            val controller =
                SessionsViewModel(
                    dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                    backgroundScope,
                )
            controller.connectDeletion(operations)
            controller.requestDeletion(listOf("new", "ok", "ok", "running", "cleanup"))
            controller.confirmDeletion { id ->
                cleaned += id
                if (id == "cleanup") error("local cleanup failed")
            }
            controller.confirmDeletion {
                fail("A repeated confirmation must not start another deletion")
            }
            runCurrent()
            assertTrue(controller.deletionState.value.busy)
            assertEquals(setOf("ok"), controller.deletionState.value.deleting)
            controller.dismissDeletion()
            assertTrue(controller.deletionState.value.busy)
            first.complete(Unit)
            runCurrent()
            assertEquals(listOf("ok", "running", "cleanup"), requested)
            assertEquals(listOf("ok", "cleanup"), cleaned)
            val state = controller.deletionState.value
            assertFalse(state.busy)
            assertTrue(state.deleting.isEmpty())
            assertEquals(SessionDeletionSupport.Supported, state.support)
            assertTrue(state.resultDialog)
            assertEquals(listOf("ok", "cleanup"), state.result!!.deleted)
            assertEquals(mapOf("running" to "session-running"), state.result.failures)
            assertEquals(mapOf("cleanup" to "cleanup-failed"), state.result.cleanupFailures)
        }

    @Test
    fun disconnectCancelsDeletionAndRetiredWorkCannotOverwriteTheNextConnection() = runTest {
        val pending = CompletableDeferred<Unit>()
        var cancelled = false
        val cleaned = mutableListOf<String>()
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
            )
        controller.connectDeletion(
            Operations {
                try {
                    pending.await()
                } finally {
                    cancelled = true
                }
            },
        )
        controller.requestDeletion(listOf("old"))
        controller.confirmDeletion { cleaned += it }
        runCurrent()
        assertTrue(controller.deletionState.value.busy)
        controller.connectDeletion(Operations {})
        controller.requestDeletion(listOf("current"))
        controller.confirmDeletion { cleaned += it }
        runCurrent()
        pending.complete(Unit)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf("current"), cleaned)
        assertEquals(listOf("current"), controller.deletionState.value.result!!.deleted)
        assertFalse(controller.deletionState.value.busy)
    }

    @Test
    fun aRetiredConfirmationDoesNotCallTheRepository() = runTest {
        val operations = Operations { fail("A retired connection must not delete") }
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
            )
        controller.connectDeletion(operations)
        controller.requestDeletion(listOf("session"))
        operations.active = false
        controller.confirmDeletion { fail("Unacknowledged deletion must not clean up") }
        runCurrent()
        assertTrue(controller.deletionState.value.result!!.retired)
        assertFalse(controller.deletionState.value.confirming)
        assertFalse(controller.deletionState.value.busy)
    }

    @Test
    fun singleDeleteKeepsTheAcknowledgementWhenCleanupFailsAndPublishesHostDenial() = runTest {
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
            )
        controller.connectDeletion(Operations {})
        val cleanup = async { controller.deleteSingle("deleted") { error("cleanup") } }
        runCurrent()
        cleanup.await()
        assertEquals(listOf("deleted"), controller.deletionState.value.result!!.deleted)
        assertTrue(controller.deletionState.value.resultDialog)
        assertTrue(controller.deletionState.value.result!!.failures.isEmpty())
        assertEquals(
            mapOf("deleted" to "cleanup-failed"),
            controller.deletionState.value.result!!.cleanupFailures,
        )
        assertEquals(SessionDeletionSupport.Supported, controller.deletionState.value.support)
        controller.connectDeletion(Operations { throw SessionOperationException("unavailable") })
        val denial = async {
            try {
                controller.deleteSingle("denied") { fail("Denied deletion must not clean up") }
                error("Host denial must reach the conversation dialog")
            } catch (error: SessionOperationException) {
                error.code
            }
        }
        runCurrent()
        assertEquals("unavailable", denial.await())
        assertTrue(controller.deletionState.value.result!!.deleted.isEmpty())
        assertEquals(
            mapOf("denied" to "unavailable"),
            controller.deletionState.value.result!!.failures,
        )
        assertEquals(SessionDeletionSupport.Unsupported, controller.deletionState.value.support)
        assertFalse(controller.deletionState.value.resultDialog)
    }
}

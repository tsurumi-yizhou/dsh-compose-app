package dev.fughetta.dshcompose.ui.sessions

import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOperationsViewModelTest {
    private class Operations : dev.fughetta.dshcompose.testing.TestSessionsRepository() {
        override var active = true
        var renaming: suspend (String, String) -> String = { _, _ -> error("unused") }
        var forking: suspend (String) -> String = { error("unused") }
        var removing: suspend (String) -> Unit = { error("unused") }

        override suspend fun rename(sessionId: String, title: String) = renaming(sessionId, title)

        override suspend fun forkSession(sessionId: String, atSeq: Long?) = forking(sessionId)

        override suspend fun forkState(
            sessionId: String,
        ): dev.fughetta.dshcompose.domain.sessions.SessionForkState? = error("unused")

        override suspend fun updateQueuedPrompt(
            sessionId: String,
            itemId: String,
            change: QueueChange,
        ): Unit = error("unused")

        override suspend fun delete(sessionId: String) = removing(sessionId)

        override suspend fun createdAt(sessionId: String): Long? = error("unused")

        override suspend fun create(
            cwd: String?,
            workspaceId: String?,
            agentPreset: String?,
        ): String = error("unused")
    }

    @Test
    fun renameWaitsForTheHostAndKeepsEditableInputAfterFailure() = runTest {
        val answers = Channel<Result<String>>(Channel.UNLIMITED)
        val inputs = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val operations =
            Operations().apply {
                renaming = { _, title ->
                    inputs += title
                    answers.receive().getOrThrow()
                }
            }
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
            )
        controller.connect(operations, true)
        controller.open("id", SessionOperation.Rename, "Old")
        controller.editTitle("  New  ")
        fun confirm() = controller.confirm({ id, title -> renamed += id to title }, {}, {})
        confirm()
        confirm()
        runCurrent()
        assertEquals(listOf("New"), inputs)
        assertTrue(controller.dialogState.value!!.busy)
        assertTrue(renamed.isEmpty())
        controller.editTitle("ignored")
        controller.dismiss()
        assertEquals("  New  ", controller.dialogState.value!!.title)
        answers.send(Result.failure(SessionOperationException("writer-held")))
        runCurrent()
        assertEquals("writer-held", controller.dialogState.value!!.errorCode)
        assertFalse(controller.dialogState.value!!.busy)
        controller.editTitle("  Retry  ")
        assertNull(controller.dialogState.value!!.errorCode)
        confirm()
        runCurrent()
        answers.send(Result.success("Host title"))
        runCurrent()
        assertEquals(listOf("New", "Retry"), inputs)
        assertEquals(listOf("id" to "Host title"), renamed)
        assertNull(controller.dialogState.value)
    }

    @Test
    fun replacingTheConnectionCancelsForkAndOnlyTheCurrentReplyNavigates() = runTest {
        val oldAnswer = CompletableDeferred<String>()
        val opened = mutableListOf<String>()
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
            )
        controller.connect(Operations().apply { forking = { oldAnswer.await() } }, true)
        controller.open("old", SessionOperation.Fork, "Old")
        controller.confirm({ _, _ -> }, { opened += it }, {})
        runCurrent()
        assertTrue(opened.isEmpty())
        assertTrue(controller.dialogState.value!!.busy)
        controller.connect(Operations().apply { forking = { "host-created" } }, true)
        assertNull(controller.dialogState.value)
        controller.open("current", SessionOperation.Fork, "Current")
        controller.confirm({ _, _ -> }, { opened += it }, {})
        runCurrent()
        oldAnswer.complete("retired")
        runCurrent()
        assertEquals(listOf("host-created"), opened)
        assertNull(controller.dialogState.value)
    }

    @Test
    fun deletionSharesItsBusyStateAndPreservesHostFailuresInTheDialog() = runTest {
        val answer = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        val operations = Operations().apply { removing = { answer.await() } }
        val controller = SessionsViewModel(operations, backgroundScope)
        controller.connect(operations, true)
        controller.open("id", SessionOperation.Delete, "Title")
        controller.confirm({ _, _ -> }, {}, { cleaned += it })
        runCurrent()
        assertTrue(controller.deletionState.value.busy)
        assertTrue(cleaned.isEmpty())
        controller.open("another", SessionOperation.Rename, "Another")
        assertEquals("id", controller.dialogState.value!!.sessionId)
        answer.complete(Unit)
        runCurrent()
        assertEquals(listOf("id"), cleaned)
        assertEquals(listOf("id"), controller.deletionState.value.result!!.deleted)
        assertNull(controller.dialogState.value)
        operations.removing = { throw SessionOperationException("unavailable") }
        controller.open("denied", SessionOperation.Delete, "Denied")
        controller.confirm({ _, _ -> }, {}, { fail("Denied deletion must not clean up") })
        runCurrent()
        assertEquals("unavailable", controller.dialogState.value!!.errorCode)
        assertFalse(controller.dialogState.value!!.busy)
        assertEquals(SessionDeletionSupport.Unsupported, controller.deletionState.value.support)
        assertFalse(controller.deletionState.value.resultDialog)
    }

    @Test
    fun offlineConfirmationKeepsTheFormAndRecoveryUsesTheSameInput() = runTest {
        var requests = 0
        val operations =
            Operations().apply {
                renaming = { _, title ->
                    requests++
                    title
                }
            }
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
            )
        controller.connect(operations, false)
        controller.open("id", SessionOperation.Rename, "Keep")
        controller.confirm({ _, _ -> }, {}, {})
        runCurrent()
        assertEquals("not-connected", controller.dialogState.value!!.errorCode)
        assertEquals(0, requests)
        controller.connected(true)
        controller.confirm({ _, title -> assertEquals("Keep", title) }, {}, {})
        runCurrent()
        assertEquals(1, requests)
        assertNull(controller.dialogState.value)
    }

    @Test
    fun renameWithoutALinkGoesToTheLocalQueue() = runTest {
        val queued = mutableListOf<Pair<String, String>>()
        val renamed = mutableListOf<Pair<String, String>>()
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
                offlineRename = { id, title ->
                    queued += id to title
                    title
                },
            )
        controller.open("id", SessionOperation.Rename, "Old")
        controller.editTitle("  Offline  ")

        controller.confirm({ id, title -> renamed += id to title }, {}, {})
        runCurrent()

        assertEquals(listOf("id" to "Offline"), queued)
        assertEquals(listOf("id" to "Offline"), renamed)
        assertNull(controller.dialogState.value)
    }

    @Test
    fun otherOperationsStillNeedAConnection() = runTest {
        val controller =
            SessionsViewModel(
                dev.fughetta.dshcompose.testing.TestSessionsRepository(),
                backgroundScope,
                offlineRename = { _, title -> title },
            )
        controller.open("id", SessionOperation.Fork, "Old")

        controller.confirm(
            { _, _ -> fail("unexpected rename") },
            { fail("unexpected fork") },
            { _ -> },
        )

        assertEquals("not-connected", controller.dialogState.value?.errorCode)
    }
}

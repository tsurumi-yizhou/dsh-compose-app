package dev.fughetta.dshcompose.domain.sessions

import dev.fughetta.dshcompose.domain.messages.QueueChange
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ForkSessionUseCaseTest {
    private class Repository : TestSessionsRepository() {
        override var active = true
        val calls = mutableListOf<String>()
        var stateRead: suspend () -> SessionForkState? = {
            SessionForkState("Inherited", emptyList())
        }
        var remove: suspend (String) -> Unit = {}
        var renamed: String? = null

        override suspend fun forkSession(sessionId: String, atSeq: Long?): String {
            calls += "fork:$sessionId:$atSeq"
            return "confirmed-child"
        }

        override suspend fun forkState(sessionId: String): SessionForkState? {
            calls += "state:$sessionId"
            return stateRead()
        }

        override suspend fun updateQueuedPrompt(
            sessionId: String,
            itemId: String,
            change: QueueChange,
        ) {
            calls += "remove:$sessionId:$itemId"
            remove(itemId)
        }

        override suspend fun rename(sessionId: String, title: String): String {
            calls += "rename:$sessionId"
            renamed = title
            return "Host title"
        }

        override suspend fun create(
            cwd: String?,
            workspaceId: String?,
            agentPreset: String?,
        ): String = error("unused")

        override suspend fun createdAt(sessionId: String): Long? = error("unused")

        override suspend fun delete(sessionId: String): Unit = error("unused")
    }

    @Test
    fun confirmedForkReportsRemainingQueueAndKeepsTheHostsTitle() = runBlocking {
        val repository =
            Repository().apply {
                stateRead = {
                    SessionForkState("Inherited", listOf("removed", "gone", "blocked", null))
                }
                remove = { id ->
                    when (id) {
                        "gone" -> throw SessionOperationException("session/queue-item-not-found")
                        "blocked" -> throw SessionOperationException("denied")
                    }
                }
            }
        val result = ForkSessionUseCase(repository)("source", 42, "Topic (99999999999999999999)")
        assertEquals("confirmed-child", result.sessionId)
        assertEquals("Host title", result.title)
        assertEquals("Topic (100000000000000000000)", repository.renamed)
        assertEquals("话题（3）", bumpedForkTitle("话题（2）"))
        assertEquals(2, result.leftQueued)
        assertFalse(result.cleanupUnknown)
        assertEquals(setOf("fork/queue-cleanup-incomplete"), result.warnings)
        assertEquals(
            listOf(
                "fork:source:42",
                "state:confirmed-child",
                "remove:confirmed-child:removed",
                "remove:confirmed-child:gone",
                "remove:confirmed-child:blocked",
                "rename:confirmed-child",
            ),
            repository.calls,
        )
    }

    @Test
    fun unavailableCleanupPreservesTheAcknowledgedForkWithoutInventingAQueueResult() = runBlocking {
        val repository =
            Repository().apply {
                stateRead = { throw SessionOperationException("connection-lost") }
            }
        val result = ForkSessionUseCase(repository)("source", 42)
        assertEquals("confirmed-child", result.sessionId)
        assertTrue(result.cleanupUnknown)
        assertNull(result.title)
        assertEquals(setOf("fork/projections-unavailable"), result.warnings)
        assertEquals(listOf("fork:source:42", "state:confirmed-child"), repository.calls)
    }

    @Test
    fun cancellationStopsCleanupAndRetiredConnectionsDoNotCreateAnotherFork() = runBlocking {
        val repository =
            Repository().apply { stateRead = { throw CancellationException("retired") } }
        try {
            ForkSessionUseCase(repository)("source", 42)
            fail("Cancellation must stop the workflow")
        } catch (_: CancellationException) {}
        repository.active = false
        try {
            ForkSessionUseCase(repository)("source", 42)
            fail("The retired connection must not create a child")
        } catch (error: SessionOperationException) {
            assertEquals("connection-lost", error.code)
        }
        assertEquals(listOf("fork:source:42", "state:confirmed-child"), repository.calls)
    }
}

package dev.fughetta.dshcompose.domain.sessions

import dev.fughetta.dshcompose.domain.messages.QueueChange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeleteSessionsUseCaseTest {
    private class Repository(val remove: suspend (String) -> Unit) : TestSessionsRepository() {
        override var active = true

        override suspend fun delete(sessionId: String) = remove.invoke(sessionId)

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

        override suspend fun createdAt(sessionId: String): Long? = error("unused")
    }

    @Test
    fun deletionKeepsHostErrorsAndStopsWhenOutcomeIsUnknown() = runBlocking {
        val requests = mutableListOf<String>()
        val cleaned = mutableListOf<String>()
        val repository = Repository { id ->
            requests += id
            if (id == "active") throw SessionOperationException("session-running")
            if (id == "lost") throw SessionOperationException("connection-lost")
        }
        val result =
            DeleteSessionsUseCase(repository)(listOf("ok", "active", "ok", "lost", "later")) {
                cleaned += it
            }
        assertEquals(listOf("ok", "active", "lost"), requests)
        assertEquals(listOf("ok"), cleaned)
        assertEquals(cleaned, result.deleted)
        assertEquals(
            mapOf(
                "active" to "session-running",
                "lost" to "connection-lost",
                "later" to "not-attempted",
            ),
            result.failures,
        )
    }

    @Test
    fun lateDeletionReplyCannotCleanUpAnotherConnection() = runBlocking {
        lateinit var repository: Repository
        repository = Repository { repository.active = false }
        val result =
            DeleteSessionsUseCase(repository)(listOf("a", "b")) { fail("old host cleanup") }
        assertTrue(result.retired)
        assertTrue(result.deleted.isEmpty())
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun acknowledgedDeletionStaysDeletedIfLocalCleanupFails() = runBlocking {
        val result = DeleteSessionsUseCase(Repository {})(listOf("a")) { error("local cleanup") }
        assertEquals(listOf("a"), result.deleted)
        assertTrue(result.failures.isEmpty())
        assertEquals(mapOf("a" to "cleanup-failed"), result.cleanupFailures)
    }
}

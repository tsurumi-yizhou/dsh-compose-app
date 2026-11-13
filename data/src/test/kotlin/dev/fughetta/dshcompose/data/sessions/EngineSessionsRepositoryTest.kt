package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.testing.SessionHost
import dev.fughetta.dshcompose.data.testing.connect
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.SessionOperationException
import dev.fughetta.dshcompose.protocol.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineSessionsRepositoryTest {
    private fun row(id: String) = SessionSummary(id, id, null, false, false, true, null, null, 10)

    private open class Source : SessionHost() {
        override suspend fun list() = emptyList<SessionSummary>()

        override suspend fun create(cwd: String?, workspaceId: String?, agentPreset: String?) =
            "created"

        override suspend fun rename(id: String, title: String) = title

        override suspend fun forkSession(id: String, atSeq: Long?) = "forked"

        override suspend fun forkState(
            id: String,
        ): dev.fughetta.dshcompose.domain.sessions.SessionForkState? = error("unused")

        override suspend fun updateQueuedPrompt(
            id: String,
            itemId: String,
            change: QueueChange,
        ): Unit = error("unused")

        override suspend fun createdAt(id: String): Long? = null

        override suspend fun delete(id: String) {}
    }

    @Test
    fun forkWorkflowUsesTheBoundSourceAndPreservesQueueAcknowledgements() = runTest {
        val requests = mutableListOf<String>()
        val source =
            object : Source() {
                override suspend fun forkSession(id: String, atSeq: Long?): String {
                    requests += "$id:$atSeq"
                    return "child"
                }

                override suspend fun forkState(id: String) =
                    SessionForkState("Title", listOf("gone"))

                override suspend fun updateQueuedPrompt(
                    id: String,
                    itemId: String,
                    change: QueueChange,
                ) {
                    throw HostRemoteException("session/queue-item-not-found")
                }
            }
        val repository = EngineSessionsRepository()
        val identity = source.client
        repository.connect(source, backgroundScope)
        val operations = repository.operationsFor(identity)!!
        val result = ForkSessionUseCase(operations)("parent", 42)
        assertEquals(listOf("parent:42"), requests)
        assertEquals("child", result.sessionId)
        assertEquals("Title (1)", result.title)
        assertEquals(0, result.leftQueued)
        assertTrue(result.warnings.isEmpty())
        repository.disconnect()
        try {
            ForkSessionUseCase(operations)("parent", 42)
            fail("Retired operations must not create a child")
        } catch (error: SessionOperationException) {
            assertEquals("connection-lost", error.code)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun creationHistoryConcurrencyIsBoundedAcrossDistinctRequests() = runTest {
        val entered = Channel<String>(Channel.UNLIMITED)
        val answers = Channel<Long>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val source =
            object : Source() {
                override suspend fun createdAt(id: String): Long? {
                    val concurrent = active.incrementAndGet()
                    maximum.accumulateAndGet(concurrent, ::maxOf)
                    try {
                        entered.send(id)
                        return answers.receive()
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        val repository = EngineSessionsRepository()
        val identity = source.client
        repository.connect(source, backgroundScope)
        try {
            val operations = repository.operationsFor(identity)!!
            val requests = listOf("a", "b", "c").map { id -> async { operations.createdAt(id) } }
            entered.receive()
            entered.receive()
            runCurrent()
            assertEquals(2, active.get())
            answers.send(10L)
            entered.receive()
            answers.send(20L)
            answers.send(30L)
            assertEquals(listOf(10L, 20L, 30L), requests.awaitAll().filterNotNull().sorted())
            assertEquals(2, maximum.get())
        } finally {
            repository.close()
        }
    }

    @Test
    fun creationReadsAreSharedAcrossObserversAndRetireWithTheirConnection() = runTest {
        var reads = 0
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val answer = CompletableDeferred<Long?>()
        val slowEntered = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Unit>()
        val source =
            object : Source() {
                override suspend fun createdAt(id: String): Long? {
                    reads++
                    if (id == "slow") {
                        slowEntered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            slowCancelled.complete(Unit)
                        }
                    }
                    entered.send(Unit)
                    return answer.await()
                }
            }
        val repository = EngineSessionsRepository()
        val identity = source.client
        repository.connect(source, backgroundScope)
        try {
            val operations = repository.operationsFor(identity)!!
            val leaving = async { operations.createdAt("same-id") }
            entered.receive()
            val remaining = async { repository.operationsFor(identity)!!.createdAt("same-id") }
            runCurrent()
            leaving.cancelAndJoin()
            answer.complete(10L)
            assertEquals(10L, remaining.await())
            assertEquals(10L, operations.createdAt("same-id"))
            assertEquals(1, reads)
            val pending = async { operations.createdAt("slow") }
            slowEntered.await()
            val nextSource =
                object : Source() {
                    override suspend fun createdAt(id: String) = 20L
                }
            val next = nextSource.client
            repository.connect(nextSource, backgroundScope)
            slowCancelled.await()
            try {
                pending.await()
                fail("Replacing the connection must cancel its pending history read")
            } catch (_: CancellationException) {
                // A retired read is cancelled with its connection owner.
            }
            assertEquals(20L, repository.operationsFor(next)!!.createdAt("same-id"))
            try {
                operations.createdAt("same-id")
                fail("Retired operations must not serve another connection's metadata")
            } catch (error: SessionOperationException) {
                assertEquals("connection-lost", error.code)
            }
        } finally {
            repository.close()
        }
    }

    @Test
    fun creationReadFailuresStayTypedAndReconnectRetriesThem() = runTest {
        var reads = 0
        var denied = true
        val source =
            object : Source() {
                override suspend fun createdAt(id: String): Long? {
                    reads++
                    if (denied) throw HostRemoteException("session/history-denied")
                    return null
                }
            }
        val repository = EngineSessionsRepository()
        val first = source.client
        repository.connect(source, backgroundScope)
        try {
            val operations = repository.operationsFor(first)!!
            repeat(2) {
                try {
                    operations.createdAt("missing")
                    fail("A denied history read must preserve its host failure")
                } catch (error: SessionOperationException) {
                    assertEquals("session/history-denied", error.code)
                }
            }
            assertEquals(1, reads)
            denied = false
            val next = source.client
            repository.connect(source, backgroundScope)
            val current = repository.operationsFor(next)!!
            assertNull(current.createdAt("missing"))
            assertNull(current.createdAt("missing"))
            assertEquals(2, reads)
        } finally {
            repository.close()
        }
    }

    @Test
    fun catalogFailuresAreDomainErrorsAndTheNextRefreshClearsThem() = runTest {
        var failing = true
        val source =
            object : Source() {
                override suspend fun list(): List<SessionSummary> {
                    if (failing) throw HostRemoteException("session/list-denied")
                    return listOf(row("confirmed"))
                }
            }
        val repository = EngineSessionsRepository()
        val catalog: SessionsRepository = repository
        repository.disconnect(loading = true)
        assertTrue(catalog.state.value.loading)
        assertFalse(catalog.state.value.available)
        repository.connectionAttemptFinished()
        assertFalse(catalog.state.value.loading)
        repository.connect(source, backgroundScope)
        assertTrue(catalog.state.value.loading)
        assertTrue(catalog.state.value.available)
        try {
            try {
                catalog.refresh()
                fail("A denied catalog read must fail")
            } catch (error: SessionOperationException) {
                assertEquals("session/list-denied", error.code)
            }
            assertEquals("session/list-denied", catalog.state.value.failure!!.code)
            failing = false
            catalog.refresh()
            assertNull(catalog.state.value.failure)
            assertEquals("confirmed", catalog.state.value.sessions.single().sessionId)
        } finally {
            repository.close()
        }
    }

    @Test
    fun sharedRefreshCannotUndoAcknowledgedDeleteOrRename() = runTest {
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val answers = Channel<List<SessionSummary>>(Channel.UNLIMITED)
        var reads = 0
        val source =
            object : Source() {
                override suspend fun list(): List<SessionSummary> {
                    reads++
                    entered.send(Unit)
                    return answers.receive()
                }
            }
        val identity = source.client
        val repository = EngineSessionsRepository()
        repository.connect(source, backgroundScope)
        try {
            val initial = async { repository.refresh() }
            entered.receive()
            answers.send(listOf(row("deleted"), row("renamed")))
            initial.await()
            val first = async { repository.refresh() }
            val second = async { repository.refresh() }
            entered.receive()
            runCurrent()
            val operations = repository.operationsFor(identity)!!
            operations.delete("deleted")
            operations.rename("renamed", "confirmed")
            answers.send(listOf(row("deleted"), row("renamed")))
            awaitAll(first, second)
            assertEquals(2, reads)
            assertEquals(listOf("renamed"), repository.snapshot().map { it.sessionId })
            assertEquals("confirmed", repository.get("renamed")?.title)
            assertEquals(listOf("renamed"), repository.state.value.allSessions.map { it.sessionId })
            assertEquals("confirmed", repository.state.value.allSessions.single().title)
            assertFalse(repository.state.value.loading)
            assertEquals("connection-lost", sessionDeletionFailure(HostTransportException()))
            assertEquals(
                "protocol-error",
                sessionDeletionFailure(HostProtocolException("bad reply")),
            )
        } finally {
            repository.close()
        }
    }

    @Test
    fun replacingConnectionCancelsOldReadAndMutationAndRetiresItsOperations() = runTest {
        val reading = CompletableDeferred<Unit>()
        val writing = CompletableDeferred<Unit>()
        var readCancelled = false
        var writeCancelled = false
        val old =
            object : Source() {
                override suspend fun list(): List<SessionSummary> {
                    reading.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        readCancelled = true
                    }
                }

                override suspend fun rename(id: String, title: String): String {
                    writing.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        writeCancelled = true
                    }
                }
            }
        val identity = old.client
        val repository = EngineSessionsRepository()
        repository.connect(old, backgroundScope)
        try {
            val operations = repository.operationsFor(identity)!!
            val read = async { repository.refresh() }
            val write = async { operations.rename("old", "pending") }
            reading.await()
            writing.await()
            repository.connect(
                object : Source() {
                    override suspend fun list() = listOf(row("new"))
                },
                backgroundScope,
            )
            read.join()
            write.join()
            assertTrue(read.isCancelled)
            assertTrue(write.isCancelled)
            assertTrue(readCancelled)
            assertTrue(writeCancelled)
            assertFalse(operations.active)
            repository.refresh()
            assertEquals(listOf("new"), repository.snapshot().map { it.sessionId })
            try {
                operations.delete("old")
                fail("retired operation must not run on the new host")
            } catch (error: SessionOperationException) {
                assertEquals("connection-lost", error.code)
            }
        } finally {
            repository.close()
        }
    }
}

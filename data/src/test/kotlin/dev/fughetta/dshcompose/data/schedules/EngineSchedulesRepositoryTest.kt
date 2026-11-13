package dev.fughetta.dshcompose.data.schedules

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineSchedulesRepositoryTest {
    private class Source(var row: String) : AutoCloseable {
        var reads = 0
        var catalogGate: CompletableDeferred<Unit>? = null
        var listGate: CompletableDeferred<Unit>? = null
        val catalogStarted = Channel<Unit>(Channel.UNLIMITED)
        val listStarted = Channel<Unit>(Channel.UNLIMITED)

        private fun rows() = buildJsonArray {
            addJsonObject {
                put("id", row)
                put("title", row)
                put("prompt", "prompt")
                put("kind", "daily")
                put("status", "active")
                put("updatedAt", "revision")
                putJsonObject("target") { put("kind", "new") }
            }
        }

        val host = ProtocolHost { endpoint, _ ->
            when (endpoint) {
                "schedule/catalog" -> {
                    reads++
                    catalogStarted.send(Unit)
                    catalogGate?.await()
                    rows()
                }
                "schedule/list" -> {
                    listStarted.send(Unit)
                    listGate?.let { withContext(NonCancellable) { it.await() } }
                    buildJsonObject {
                        put("items", rows())
                        put("total", 1)
                        put("offset", 0)
                        put("shown", 1)
                    }
                }
                "schedule/run" -> JsonObject(emptyMap())
                else -> error("Unexpected request: $endpoint")
            }
        }

        suspend fun connect(repository: EngineSchedulesRepository, scope: CoroutineScope) {
            host.client.mux.ready()
            repository.connect(host.client, scope)
        }

        override fun close() = host.close()
    }

    @Test
    fun catalogIsSharedAcrossReadersAndRefreshedAfterHostEventsAndWrites() = runBlocking {
        withTimeout(10000) {
            Source("first").use { source ->
                val repository = EngineSchedulesRepository()
                try {
                    source.connect(repository, this)
                    val operations = repository.connection.value!!
                    source.catalogGate = CompletableDeferred()
                    val cancelledReader = async { operations.catalog() }
                    source.catalogStarted.receive()
                    val retainedReader =
                        async(start = CoroutineStart.UNDISPATCHED) { operations.catalog() }
                    assertEquals(1, source.reads)
                    cancelledReader.cancelAndJoin()
                    source.catalogGate!!.complete(Unit)
                    assertEquals("first", retainedReader.await().single().id)
                    assertEquals("first", operations.catalog().single().id)
                    assertEquals(1, source.reads)
                    source.row = "second"
                    val changed =
                        async(start = CoroutineStart.UNDISPATCHED) { operations.changes.first() }
                    source.host.emit(
                        buildJsonObject {
                            put("type", "emit")
                            put("event", "schedule/changed")
                        },
                    )
                    changed.await()
                    val refreshed = operations.catalog()
                    assertEquals("second", refreshed.single().id)
                    assertEquals(2, source.reads)
                    operations.run(refreshed.single())
                    operations.catalog()
                    assertEquals(3, source.reads)
                } finally {
                    repository.close()
                }
            }
        }
    }

    @Test
    fun switchingHostsRetiresCachedDataAndDiscardsLateRequestResults() = runBlocking {
        withTimeout(10000) {
            Source("old").use { oldSource ->
                Source("new").use { nextSource ->
                    val repository = EngineSchedulesRepository()
                    try {
                        oldSource.connect(repository, this)
                        val old = repository.connection.value!!
                        old.catalog()
                        oldSource.listGate = CompletableDeferred()
                        val pending = async { old.list(null, 0) }
                        oldSource.listStarted.receive()
                        nextSource.connect(repository, this)
                        oldSource.listGate!!.complete(Unit)
                        assertFalse(old.active)
                        try {
                            pending.await()
                            fail("Retired reads must be cancelled")
                        } catch (_: CancellationException) {}
                        try {
                            old.catalog()
                            fail("Retired cache must not be returned")
                        } catch (_: CancellationException) {}
                        assertEquals(
                            "new",
                            repository.connection.value!!.list(null, 0).items.single().id,
                        )
                    } finally {
                        oldSource.listGate?.complete(Unit)
                        repository.close()
                    }
                }
            }
        }
    }
}

package dev.fughetta.dshcompose.data.providers

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineProvidersRepositoryTest {
    private val response =
        Json.parseToJsonElement(
            """{"namespaces":[{"ns":"llm-pi-ai","revision":17,"value":{"providers":{}}}]}""",
        )

    private class Source : AutoCloseable {
        var answer: suspend () -> JsonElement = { error("Unexpected read") }
        val started = Channel<Unit>(Channel.UNLIMITED)
        var writeFailure: Exception? = null
        private val host = ProtocolHost { endpoint, _ ->
            when (endpoint) {
                "settings/describe" -> {
                    started.send(Unit)
                    answer()
                }
                "credentials/describe" -> JsonObject(emptyMap())
                "credentials/unset" -> {
                    writeFailure?.let { throw it }
                    JsonNull
                }
                else -> error("Unexpected request: $endpoint")
            }
        }
        val client
            get() = host.client

        override fun close() = host.close()
    }

    @Test
    fun onlyAcknowledgedWritesNotifyConsumersAndRetiredReadsCannotReplaceTheCurrentSnapshot() =
        runBlocking {
            withTimeout(5000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                var changes = 0
                val repository = EngineProvidersRepository { changes++ }
                val source = Source().apply { answer = { response } }.also { it.client.mux.ready() }
                val replacementSource = Source().apply { answer = { response } }
                val finish = CompletableDeferred<JsonElement>()
                val owner = repository.connect(source.client, scope)
                try {
                    val operations = repository.connection.value!!
                    val snapshot = operations.refresh()
                    assertEquals(17L, snapshot.settings?.revision)
                    assertEquals(
                        listOf(ProviderAccount("builtin-deepseek", "DeepSeek", "DEEPSEEK_API_KEY")),
                        snapshot.accounts,
                    )
                    assertTrue(snapshot.credentials.isEmpty())
                    source.started.receive()
                    source.writeFailure = HostRemoteException("credentials/read-only")
                    try {
                        operations.setCredential("KEY", null)
                        fail()
                    } catch (error: ProviderOperationException) {
                        assertEquals("credentials/read-only", error.code)
                    }
                    assertEquals(0, changes)
                    source.writeFailure = null
                    operations.setCredential("KEY", null)
                    assertEquals(1, changes)
                    assertEquals(1L, operations.changes.value)
                    assertSame(snapshot, operations.snapshot.value)
                    source.answer = { withContext(NonCancellable) { finish.await() } }
                    val late = async { operations.refresh() }
                    source.started.receive()
                    source.client.mux.reconnect()
                    source.client.mux.ready()
                    val next = repository.connection.first { it != null && it !== operations }!!
                    finish.complete(response)
                    try {
                        late.await()
                        fail()
                    } catch (_: CancellationException) {}
                    assertNull(next.snapshot.value)
                    replacementSource.client.mux.ready()
                    val replacement = repository.connect(replacementSource.client, scope)
                    val current = repository.connection.value!!
                    assertNotSame(next, current)
                    try {
                        next.refresh()
                        fail("A retired repository must not read from the replacement source")
                    } catch (_: CancellationException) {}
                    assertTrue(replacementSource.started.tryReceive().isFailure)
                    owner.close()
                    assertSame(current, repository.connection.value)
                    assertEquals(17L, current.refresh().settings?.revision)
                    replacement.close()
                } finally {
                    finish.complete(response)
                    owner.close()
                    repository.close()
                    source.close()
                    replacementSource.close()
                    scope.cancel()
                }
            }
        }
}

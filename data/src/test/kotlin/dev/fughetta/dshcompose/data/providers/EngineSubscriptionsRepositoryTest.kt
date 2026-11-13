package dev.fughetta.dshcompose.data.providers

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineSubscriptionsRepositoryTest {
    private class Source : AutoCloseable {
        val beginStarted = Channel<Unit>(Channel.UNLIMITED)
        var acknowledgment = CompletableDeferred<String>()
        val cancelled = Channel<String>(Channel.UNLIMITED)
        var step = LoginStep(LoginPhase.Prompt)
        private val host = ProtocolHost { endpoint, args ->
            when (endpoint) {
                "appAuth/begin" -> {
                    beginStarted.send(Unit)
                    buildJsonObject { put("attemptId", acknowledgment.await()) }
                }
                "appAuth/poll" -> buildJsonObject { put("phase", step.phase.name.lowercase()) }
                "appAuth/cancel" -> {
                    cancelled.send(args.getValue("attemptId").jsonPrimitive.content)
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
    fun cancelledAndRetiredBeginsReleaseTheirLateAttemptsOnTheOriginalSource() = runBlocking {
        withTimeout(5000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineSubscriptionsRepository()
            val source = Source().also { it.client.mux.ready() }
            val nextSource = Source()
            val owner = repository.connect(source.client, scope)
            try {
                val operations = repository.connection.value!!
                val cancelled = async { operations.begin(Subscription.ChatGpt) }
                source.beginStarted.receive()
                cancelled.cancel()
                source.acknowledgment.complete("abandoned")
                cancelled.join()
                assertEquals("abandoned", source.cancelled.receive())

                source.acknowledgment = CompletableDeferred()
                val retired = async { operations.begin(Subscription.ChatGpt) }
                source.beginStarted.receive()
                nextSource.client.mux.ready()
                val next = repository.connect(nextSource.client, scope)
                val current = repository.connection.value!!
                source.acknowledgment.complete("retired")
                try {
                    retired.await()
                    fail()
                } catch (_: CancellationException) {}
                assertEquals("retired", source.cancelled.receive())
                assertTrue(nextSource.cancelled.tryReceive().isFailure)
                owner.close()
                assertSame(current, repository.connection.value)
                next.close()
            } finally {
                source.acknowledgment.complete("cleanup")
                nextSource.close()
                owner.close()
                repository.close()
                source.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun aTerminalAttemptNotifiesOnceAndAnOpenAttemptIsReleasedWithItsConnection() = runBlocking {
        withTimeout(5000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var changes = 0
            val repository = EngineSubscriptionsRepository { changes++ }
            val source =
                Source()
                    .also { it.client.mux.ready() }
                    .apply { acknowledgment.complete("finished") }
            val owner = repository.connect(source.client, scope)
            try {
                val operations = repository.connection.value!!
                val login = operations.begin(Subscription.ChatGpt)
                assertEquals(LoginPhase.Prompt, login.poll().phase)
                source.step = LoginStep(LoginPhase.Done)
                assertEquals(LoginPhase.Done, login.poll().phase)
                login.close()
                assertEquals(1, changes)
                assertTrue(source.cancelled.tryReceive().isFailure)
                source.acknowledgment = CompletableDeferred<String>().apply { complete("pending") }
                operations.begin(Subscription.Claude)
                owner.close()
                assertEquals("pending", source.cancelled.receive())
                assertNull(repository.connection.value)
            } finally {
                owner.close()
                repository.close()
                source.close()
                scope.cancel()
            }
        }
    }
}

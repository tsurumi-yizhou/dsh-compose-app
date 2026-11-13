package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MessageTranslationsTest {
    private class Source(private val id: String = "client") : AutoCloseable {
        var answer: suspend () -> String = { "Translated" }
        val requests = mutableListOf<List<String>>()
        private val transport =
            dev.fughetta.dshcompose.data.testing.ProtocolHost(id) { endpoint, args ->
                when (endpoint) {
                    "cotTranslation/limits" ->
                        buildJsonObject {
                            put("maxTextChars", 4000)
                            putJsonObject("preferences") {
                                put("provider", "provider")
                                put("targetLanguage", "auto")
                            }
                            putJsonArray("availableProviders") { add("provider") }
                        }
                    "cotTranslation/translate" -> {
                        val request = args.getValue("request").jsonObject
                        requests +=
                            listOf("sessionId", "text", "provider", "targetLanguage").map {
                                request.getValue(it).jsonPrimitive.content
                            }
                        JsonPrimitive(answer())
                    }
                    else -> error("Unexpected request: $endpoint")
                }
            }
        val client
            get() = transport.client

        override fun close() = client.close()
    }

    @Test
    fun generationChangesRetireTranslationsAndOldConnectionClosureKeepsTheNewOwner() = runBlocking {
        withTimeout(5000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = MessageTranslations()
            val source = Source()
            val nextSource = Source("another")
            val old = repository.connect(source.client, scope)
            val finish = CompletableDeferred<Unit>()
            try {
                assertNull(repository.connection.value)
                source.client.mux.ready()
                val first = repository.connection.filterNotNull().first()
                val limits = first.limits()
                val started = CompletableDeferred<Unit>()
                source.answer = {
                    started.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                    "Retired result"
                }
                val request = async {
                    first.translate("session", "Original", limits, limits.targetFor("zh"))
                }
                started.await()
                source.client.mux.reconnect()
                source.client.mux.ready()
                repository.connection.first { it != null && it !== first }
                finish.complete(Unit)
                try {
                    request.await()
                    fail("Retired translations must not publish")
                } catch (_: CancellationException) {}
                assertEquals(
                    listOf(listOf("session", "Original", "provider", "zh")),
                    source.requests,
                )
                nextSource.client.mux.ready()
                val next = repository.connect(nextSource.client, scope)
                val current = repository.connection.value!!
                old.close()
                assertSame(current, repository.connection.value)
                assertEquals(
                    "Translated",
                    current.translate("next-session", "Next", current.limits(), "en"),
                )
                next.close()
                assertNull(repository.connection.value)
            } finally {
                finish.complete(Unit)
                repository.close()
                source.close()
                nextSource.close()
                scope.cancel()
            }
        }
    }
}

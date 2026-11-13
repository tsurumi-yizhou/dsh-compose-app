package dev.fughetta.dshcompose.data.providers

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.domain.providers.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineSessionModelsRepositoryTest {
    private val catalog =
        ModelCatalog(null, persistentSetOf(), persistentListOf(), persistentListOf())

    private class Source : AutoCloseable {
        var defaults: ModelDefaults? = null
        val reads = AtomicInteger()
        val started = Channel<Unit>(Channel.UNLIMITED)
        var answer: suspend () -> JsonObject = { error("Unexpected catalog") }
        val selected =
            ModelSelectionResult(ModelSelection("host", "confirmed"), ModelDefaultSaveStatus.Failed)
        private val host = ProtocolHost { endpoint, args ->
            when (endpoint) {
                "session/modelCatalog" -> {
                    reads.incrementAndGet()
                    started.send(Unit)
                    answer()
                }
                "session/selectModel" ->
                    buildJsonObject {
                        putJsonObject("selected") {
                            put("provider", selected.selected.provider)
                            put("model", selected.selected.model)
                        }
                        putJsonObject("defaultSave") { put("status", "failed") }
                    }
                "settings/mutate" -> {
                    val previous = checkNotNull(defaults)
                    check(args.getValue("expectedRevision").jsonPrimitive.long == previous.revision)
                    val ops =
                        args
                            .getValue("ops")
                            .jsonArray
                            .map { it.jsonObject }
                            .associate {
                                it.getValue("path").jsonArray.single().jsonPrimitive.content to
                                    it["value"]
                            }
                    defaults =
                        previous.copy(
                            revision = previous.revision + 1,
                            selection =
                                ModelSelection(
                                    ops.getValue("provider")!!.jsonPrimitive.content,
                                    ops.getValue("model")!!.jsonPrimitive.content,
                                    ops["reasoningEffort"]?.jsonPrimitive?.content,
                                ),
                            rememberLastSelection =
                                ops.getValue("rememberLastSelection")!!.jsonPrimitive.boolean,
                        )
                    JsonNull
                }
                "settings/describe" ->
                    buildJsonObject {
                        putJsonArray("namespaces") {
                            defaults?.let { value ->
                                addJsonObject {
                                    put("ns", "agent-default-model")
                                    put("revision", value.revision)
                                    putJsonObject("value") {
                                        put("provider", value.selection?.provider)
                                        put("model", value.selection?.model)
                                        put("rememberLastSelection", value.rememberLastSelection)
                                    }
                                    putJsonObject("schema") {
                                        put("type", "object")
                                        putJsonObject("dict") {
                                            listOf(
                                                    "provider",
                                                    "model",
                                                    "reasoningEffort",
                                                    "rememberLastSelection",
                                                )
                                                .forEach { key ->
                                                    putJsonObject(key) {
                                                        put(
                                                            "type",
                                                            if (key == "rememberLastSelection")
                                                                "boolean"
                                                            else "string",
                                                        )
                                                    }
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                else -> error("Unexpected request: $endpoint")
            }
        }
        val client
            get() = host.client

        override fun close() = host.close()
    }

    @Test
    fun catalogReadsAreSharedAndSelectionsInvalidateTheOwnedCache() = runBlocking {
        withTimeout(5000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineSessionModelsRepository()
            val source = Source().also { it.client.mux.ready() }
            val answer = CompletableDeferred<JsonObject>()
            source.answer = { answer.await() }
            val owner = repository.connect(source.client, scope)
            try {
                val operations = repository.connection.value!!
                val cancelled = async { operations.catalog() }
                source.started.receive()
                val survivor = async(start = CoroutineStart.UNDISPATCHED) { operations.catalog() }
                cancelled.cancelAndJoin()
                answer.complete(JsonObject(emptyMap()))
                val cached = survivor.await()
                assertEquals(catalog, cached)
                assertSame(cached, operations.catalog())
                assertEquals(1, source.reads.get())
                val receipt = operations.select("session", ModelSelection("requested", "model"))
                assertEquals(source.selected, receipt)
                assertEquals(catalog, operations.catalog())
                assertEquals(2, source.reads.get())
                val original =
                    ModelDefaults(17, ModelSelection("provider", "old"), true, null, true)
                source.defaults = original
                val chosen = ModelSelection("provider", "new")
                val version = operations.changes.value
                operations.setDefaults(original, chosen, false)
                assertEquals(version + 1, operations.changes.value)
                assertEquals(
                    original.copy(revision = 18, selection = chosen, rememberLastSelection = false),
                    operations.defaults(),
                )
                assertEquals(catalog, operations.catalog())
                assertEquals(3, source.reads.get())
            } finally {
                owner.close()
                repository.close()
                source.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun changingGenerationRetiresTheCatalogAndClosingAnOldConnectionKeepsTheNewOne() = runBlocking {
        withTimeout(5000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineSessionModelsRepository()
            val source = Source().also { it.client.mux.ready() }
            val finish = CompletableDeferred<JsonObject>()
            source.answer = { withContext(NonCancellable) { finish.await() } }
            val nextSource = Source().apply { answer = { JsonObject(emptyMap()) } }
            val old = repository.connect(source.client, scope)
            try {
                val first = repository.connection.value!!
                val read = async { first.catalog() }
                source.started.receive()
                source.client.mux.reconnect()
                source.client.mux.ready()
                repository.connection.first { it != null && it !== first }
                finish.complete(JsonObject(emptyMap()))
                try {
                    read.await()
                    fail("Retired catalog must not publish")
                } catch (_: CancellationException) {}
                nextSource.client.mux.ready()
                val next = repository.connect(nextSource.client, scope)
                val current = repository.connection.value!!
                old.close()
                assertSame(current, repository.connection.value)
                assertEquals(catalog, current.catalog())
                next.close()
                assertNull(repository.connection.value)
            } finally {
                finish.complete(JsonObject(emptyMap()))
                repository.close()
                nextSource.close()
                source.close()
                scope.cancel()
            }
        }
    }
}

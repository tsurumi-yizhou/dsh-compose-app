package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderDiscoveryTest {
    @Test
    fun storedAndDraftDiscoveryOnlyReadAndMetadataIsWrittenWhenTheUserSaves() = runBlocking {
        var reads = 0
        var writes = 0
        val temporaryKey = "synthetic-discovery-only-key"
        val advertised =
            parseObject(
                """{"id":"model-one","name":"Model One","contextWindow":131072,"maxTokens":8192,"inputModalities":["text","image"]}""",
            )
        val modelSpec =
            parseObject(
                """{"id":"model-one","name":"Model One","contextWindow":131072,"maxTokens":8192,"input":["text","image"]}""",
            )
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()
            val value =
                when (input.string("method")) {
                    "llm/discoverModels" -> {
                        assertEquals(0, writes)
                        assertEquals("llm-pi-ai", args.string("settingsNs"))
                        val discovery = args["request"]!!.objectValue()
                        if (reads++ == 0)
                            assertEquals(parseObject("""{"provider":"gateway"}"""), discovery)
                        else {
                            assertEquals("https://example.test/v1", discovery.string("baseURL"))
                            assertEquals("openai-responses", discovery.string("api"))
                            assertEquals(temporaryKey, discovery.string("apiKey"))
                            assertNull(discovery["provider"])
                        }
                        JsonArray(listOf(advertised))
                    }
                    "settings/mutate" -> {
                        writes++
                        assertEquals(2, reads)
                        assertEquals(1L, args.long("expectedRevision"))
                        val profile =
                            (args["ops"] as JsonArray)
                                .single()
                                .objectValue()["value"]!!
                                .objectValue()
                        assertEquals(JsonArray(listOf(modelSpec)), profile["models"])
                        assertFalse(profile.toString().contains(temporaryKey))
                        buildJsonObject {
                            put("revision", 2)
                            putJsonObject("value") {
                                putJsonObject("providers") { put("new-route", profile) }
                            }
                            putJsonObject("user") {
                                putJsonObject("providers") { put("new-route", profile) }
                            }
                        }
                    }
                    else -> error("Discovery must not persist or read a credential value")
                }
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)

                    put("result", value)
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val stored = client.discoverModels(provider = "gateway")
            assertEquals(131072L, stored.single().contextWindow)
            val key = ProviderKey.from(temporaryKey)
            val draft = key.use {
                client.discoverModels("https://example.test/v1", ProviderApi.OpenAiResponses, it)
            }
            try {
                key.text()
                fail()
            } catch (_: IllegalStateException) {}
            assertEquals(0, writes)
            val saved =
                client.createProvider(
                    "new-route",
                    "New",
                    ProviderApi.OpenAiResponses,
                    "https://example.test/v1",
                    "NEW_ROUTE_API_KEY",
                    draft.map { it.toSpec() },
                    1,
                )
            assertEquals("Model One", saved.find("new-route")!!.models.single().name)
            assertEquals(1, writes)
        }
    }

    @Test
    fun cancellingDraftDiscoveryCancelsTheKtorReadAndReleasesItsInput() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val key = ProviderKey.from("synthetic-cancelled-input")
            val request = launch {
                key.use {
                    client.discoverModels(
                        "https://example.test/v1",
                        ProviderApi.OpenAiCompletions,
                        it,
                    )
                }
            }
            withTimeout(5000) { started.await() }
            request.cancelAndJoin()
            withTimeout(5000) { finished.await() }
            try {
                key.text()
                fail()
            } catch (_: IllegalStateException) {}
        }
    }
}

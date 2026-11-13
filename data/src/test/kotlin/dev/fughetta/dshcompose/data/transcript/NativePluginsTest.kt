package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.data.nativeplugins.*
import dev.fughetta.dshcompose.data.plugins.EnginePluginUiRepository
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativePluginsTest {
    @Test
    fun contributionReadAndInvocationShareAuthenticatedHostWithoutReplayingMutations() =
        runBlocking {
            var reads = 0
            var invokes = 0
            val engine = MockEngine { request ->
                assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                when (request.url.encodedPath) {
                    "/compose/extensions/v1/contributions" -> {
                        reads++
                        assertEquals("audio,files", request.url.parameters["features"])
                        if (reads == 2) {
                            assertEquals("\"revision\"", request.headers[HttpHeaders.IfNoneMatch])
                            respond("", HttpStatusCode.NotModified)
                        } else
                            respond(
                                """{"rev":"revision","plugins":[{"pkg":"@p/sample","state":"ok","native":true,"contributes":{"composer.actions":[{"id":"ready","label":"Ready"},{"id":"missing","label":"Missing","requires":["voice"]},{"id":"off","label":"Off","ready":false}],"panels":[{"id":"view","title":"View","placement":"plugin"}]}}]}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                    }
                    "/compose/extensions/v1/invoke" -> {
                        invokes++
                        val body =
                            Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals("host-stream", body.npString("stream"))
                        assertEquals("clip", body["context"]?.jsonObject?.npString("clipboard"))
                        assertEquals(
                            "actual-message",
                            body["scope"]?.jsonObject?.npString("messageId"),
                        )
                        respond(
                            """{"error":{"code":"dsh-compose/no-handler"}}""",
                            HttpStatusCode.Conflict,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    else -> error("Unexpected route")
                }
            }
            val connection =
                HostConnection(
                    "https://host",
                    credential = HostCredential("secret", "host"),
                    engine = engine,
                )
            val client = ProtocolClient(connection)
            val repository = EnginePluginUiRepository(client)
            try {
                val api = NativePluginApi(client)
                val caps =
                    NativePluginCaps("1.0.0", "en", setOf("text"), linkedSetOf("audio", "files"))
                val binding = repository.openNative(caps, CoroutineScope(coroutineContext))
                val first = binding.contributions(caps)!!
                assertEquals(listOf("ready"), first.plugins.single().actions.map { it.id })
                assertEquals(listOf("plugin"), first.plugins.single().panels.single().placements)
                assertSame(first, binding.contributions(caps, first))
                try {
                    api.invoke(
                        NativePluginInvocation(
                            "@p/sample",
                            "ready",
                            "message.actions",
                            NativePluginScope("s", messageId = "actual-message"),
                            context =
                                dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj(
                                    mapOf(
                                        "clipboard" to
                                            dev.fughetta.dshcompose.domain.chat.ToolPayload.Text(
                                                "clip",
                                            ),
                                    ),
                                ),
                            locale = "en",
                            stream = "host-stream",
                        ),
                    )
                    fail("Refusal must propagate")
                } catch (error: OperationFailure) {
                    assertEquals("dsh-compose/no-handler", error.code)
                }
                assertEquals(1, invokes)
                api.close()
                binding.close()
                try {
                    binding.contributions(caps)
                    fail("Retired native bindings must not make requests")
                } catch (_: kotlinx.coroutines.CancellationException) {}
                assertEquals(2, reads)
                val next = repository.openNative(caps, CoroutineScope(coroutineContext))
                assertNotNull(next.contributions(caps))
                assertEquals(3, reads)
                repository.close()
                assertFalse(next.nativeActive)
                try {
                    next.contributions(caps)
                    fail("Closing the host repository must retire its native bindings")
                } catch (_: kotlinx.coroutines.CancellationException) {}
                assertEquals(3, reads)
            } finally {
                repository.close()
                client.close()
            }
        }

    @Test
    fun toolCardsAndVisibleTurnsRetainHostIdentityAcrossIncrementalToolResults() {
        val transcript = ChatTranscript()
        fun record(seq: Long, type: String, body: String) =
            transcript.apply(
                SessionUpdate.Record(
                    SessionRecord(seq, seq, type, Json.parseToJsonElement(body).jsonObject),
                ),
            )
        record(1, "turn/start", """{"turn":73}""")
        record(
            2,
            "assistant/message",
            """{"turn":73,"step":1,"message":{"id":"message-id","content":[{"type":"text","text":"Result"}]}}""",
        )
        val assistant = transcript.items.single()
        record(3, "tool/call", """{"callId":"call","name":"plugin","arguments":"{}"}""")
        record(
            4,
            "tool/result",
            """{"message":{"toolCallId":"call","content":[{"type":"text","text":"done"}]},"meta":{"dsh-compose/view":{"type":"text","text":"Native card"}}}""",
        )
        assertSame(assistant, transcript.items.first())
        assertEquals("73", assistant.turnId)
        assertEquals("message-id", assistant.messageId)
        val tool = transcript.items.last()
        assertEquals("73", tool.turnId)
        assertEquals(ToolState.Succeeded, tool.tool?.state)
        assertEquals(
            "Native card",
            tool.tool
                ?.meta
                ?.toJsonElement()
                ?.jsonObject
                ?.get("dsh-compose/view")
                ?.jsonObject
                ?.npString("text"),
        )
    }
}

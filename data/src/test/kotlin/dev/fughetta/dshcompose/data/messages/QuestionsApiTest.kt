package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuestionsApiTest {
    @Test
    fun timedQuestionClaimsHostWaitAndRepliesWithLabelsAndCustomText() = runBlocking {
        val replied = CompletableDeferred<JsonObject>()
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        post("/compose/rpc") {
                            val input = parseObject(call.receiveText())
                            replied.complete(input["args"]!!.objectValue())
                            call.respondText(
                                buildJsonObject {
                                    put("id", input["id"]!!)
                                    put("result", JsonNull)
                                }
                                    .toString(),
                                ContentType.Application.Json,
                            )
                        }
                        webSocket("/compose/events") {
                            val events = parseObject((incoming.receive() as Frame.Text).readText())
                            suspend fun item(stream: JsonElement, value: JsonObject) {
                                send(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", stream)
                                        put("data", value)
                                    }
                                        .toString(),
                                )
                            }
                            item(
                                events["id"]!!,
                                parseObject(
                                    """{"type":"ready","clientId":"client1","host":{"home":"/root"}}""",
                                ),
                            )
                            item(
                                events["id"]!!,
                                parseObject(
                                    """{
                        "type":"waterfall","event":"user-questions/request","eventId":"q1","agentId":"s1",
                        "request":{"questions":[{"id":"destination","question":"选择目标？","multiSelect":true,
                            "options":[{"label":"手机","description":"本机"},{"label":"远程"}]}],
                            "wait":{"callId":"call1","timed":true}}
                    }""",
                                ),
                            )
                            for (raw in incoming) {
                                val open = parseObject((raw as Frame.Text).readText())
                                if (open.string("op") != "subscribe") continue
                                check(open.string("method") == "userQuestions/attachWait")
                                check(
                                    open["args"] ==
                                        parseObject("""{"agentId":"s1","callId":"call1"}"""),
                                )
                                item(
                                    open["id"]!!,
                                    buildJsonObject { put("remainingMs", 1250) },
                                )
                            }
                        }
                    }
                }
                .start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            ProtocolClient(HostConnection("http://127.0.0.1:$port")).use { client ->
                withTimeout(5000) {
                    val generation = client.mux.ready()
                    val pending = client.mux.pendingRequests.first { "q1" in it }
                    val question = decodeQuestionRequest(pending.getValue("q1"))
                    assertTrue(question.timed)
                    assertEquals(
                        listOf("手机", "远程"),
                        question.questions.single().options.map { it.label },
                    )
                    val remaining =
                        client
                            .claimQuestionWait(question.sessionId, question.waitCallId!!)
                            .filterIsInstance<MuxEvent.Item>()
                            .first()
                            .value
                            .objectValue()
                            .long("remainingMs")
                    assertEquals(1250L, remaining)
                    assertFalse(replied.isCompleted)
                    client.answerQuestion(
                        question.eventId,
                        listOf(QuestionAnswer("destination", listOf("手机"), "需要 Wi-Fi")),
                        generation,
                    )
                    assertEquals(
                        parseObject(
                            """{
                        "clientId":"client1","eventId":"q1","outcome":{"kind":"result","value":{
                            "answers":[{"id":"destination","selected":["手机"],"custom":"需要 Wi-Fi"}]}}
                    }""",
                        ),
                        replied.await(),
                    )
                    assertFalse("q1" in client.mux.pendingRequests.value)
                }
            }
        } finally {
            server.stop(0, 1000)
        }
    }
}

package dev.fughetta.dshcompose.protocol

import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun streamedUploadProducesTheReceiptUsedByThePrompt() = runBlocking {
        val content = ByteArray(128 * 1024) { (it % 251).toByte() }
        val source = temporary.newFile("source.bin").apply { writeBytes(content) }
        var uploads = 0
        var prompts = 0
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    routing {
                        post("/compose/files/upload") {
                            uploads++
                            assertEquals("s1", call.request.queryParameters["sessionId"])
                            assertEquals("work report #1.bin", call.request.queryParameters["name"])
                            assertArrayEquals(content, call.receive<ByteArray>())
                            call.respondText(
                                buildJsonObject {
                                    put("id", call.request.headers["X-Compose-Request-Id"])
                                    put("ok", true)
                                    putJsonObject("value") {
                                        put("receiptId", "receipt1")
                                        putJsonObject("file") {
                                            put("attachmentId", "file1")
                                            put("name", "work report #1.bin")
                                        }
                                    }
                                }
                                    .toString(),
                                ContentType.Application.Json,
                            )
                        }
                        post("/compose/rpc") {
                            prompts++
                            val input = parseObject(call.receiveText())
                            val request = input["args"]!!.objectValue()["request"]!!.objectValue()
                            assertEquals("s1", request.string("sessionId"))
                            assertEquals("prompt1", request.string("requestId"))
                            assertEquals(
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "file")
                                            put("receiptId", "receipt1")
                                        },
                                    ),
                                ),
                                request["content"],
                            )
                            call.respondText(
                                buildJsonObject {
                                    put("id", input["id"]!!)
                                    put("result", JsonNull)
                                }
                                    .toString(),
                                ContentType.Application.Json,
                            )
                        }
                    }
                }
                .start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            ProtocolClient(HostConnection("http://127.0.0.1:$port")).use { client ->
                val receipt = client.uploadFile("s1", source, "work report #1.bin")
                assertEquals("file1", receipt.attachment.string("attachmentId"))
                client.prompt(
                    "s1",
                    "",
                    requestId = "prompt1",
                    fileReceiptIds = listOf(receipt.receiptId),
                )
            }
            assertEquals(1, uploads)
            assertEquals(1, prompts)
        } finally {
            server.stop(0, 1000)
        }
    }
}

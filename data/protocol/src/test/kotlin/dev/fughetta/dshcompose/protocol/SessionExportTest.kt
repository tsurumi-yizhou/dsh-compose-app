package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sessions.sessionLogZipName
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SessionExportTest {
    @Test
    fun archiveDownloadsKeepAuthenticationAndStreamLargeBodiesWithoutFollowingRedirects(): Unit =
        runBlocking {
            withTimeout(30000) {
                val size = 160L * 1024 * 1024 + 13
                val redirected = AtomicInteger()
                val session = "session /?#&+%工具"
                val server =
                    embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                            routing {
                                get("/compose/sessions/export") {
                                    assertEquals(
                                        "archive.test",
                                        call.request.headers[HttpHeaders.Host],
                                    )
                                    assertEquals(
                                        "Bearer dummy",
                                        call.request.headers[HttpHeaders.Authorization],
                                    )
                                    assertEquals(
                                        "true",
                                        call.request.queryParameters["includeDescendants"],
                                    )
                                    when (val id = call.request.queryParameters["sessionId"]) {
                                        "missing" ->
                                            call.respondText(
                                                "No such session",
                                                status = HttpStatusCode.NotFound,
                                            )
                                        "redirect" -> call.respondRedirect("/redirect-target")
                                        else -> {
                                            assertEquals(session, id)
                                            call.respondBytesWriter(
                                                ContentType.Application.Zip,
                                                contentLength = size,
                                            ) {
                                                val chunk = ByteArray(64 * 1024) { 42 }
                                                var remaining = size
                                                while (remaining > 0) {
                                                    val count =
                                                        minOf(remaining, chunk.size.toLong())
                                                            .toInt()
                                                    writeFully(chunk, 0, count)
                                                    remaining -= count
                                                }
                                            }
                                        }
                                    }
                                }
                                get("/redirect-target") {
                                    redirected.incrementAndGet()
                                    call.respondText("Unexpected")
                                }
                            }
                        }
                        .start()
                val port = server.engine.resolvedConnectors().single().port
                val client =
                    ProtocolClient(
                        HostConnection(
                            "http://127.0.0.1:$port",
                            hostHeader = "archive.test",
                            credential = HostCredential("dummy", "archive.test"),
                        ),
                    )
                var written = 0L
                var maximumWrite = 0
                val output =
                    object : OutputStream() {
                        override fun write(value: Int) {
                            throw AssertionError("Expected bulk streaming")
                        }

                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            maximumWrite = maxOf(maximumWrite, length)
                            assertTrue(
                                buffer.sliceArray(offset until offset + length).all {
                                    it == 42.toByte()
                                },
                            )
                            written += length
                        }

                        override fun close() {
                            throw AssertionError("The caller owns the destination")
                        }
                    }
                var progress = 0L
                try {
                    assertTrue(client.canExportSessionLog)
                    assertEquals(
                        size,
                        client.exportSessionLog(session, output) { bytes, total ->
                            assertEquals(size, total)
                            assertTrue(bytes >= progress)
                            progress = bytes
                        },
                    )
                    assertEquals(size, progress)
                    assertEquals(size, written)
                    assertTrue(maximumWrite <= 64 * 1024)
                    for ((id, status) in listOf("missing" to 404, "redirect" to 302)) {
                        try {
                            client.exportSessionLog(id, output)
                            fail("Expected HTTP refusal")
                        } catch (error: HostRemoteException) {
                            assertEquals("session-export/http-$status", error.code)
                        }
                    }
                    assertEquals(0, redirected.get())
                    assertEquals(size, written)
                    assertEquals("dsh-session-a_______b.zip", sessionLogZipName("a/\\?%# 工b"))
                } finally {
                    client.close()
                    server.stop(0, 1000)
                }
            }
        }
}

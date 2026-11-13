package dev.fughetta.dshcompose.data.terminal

import dev.fughetta.dshcompose.domain.terminal.TerminalEnvironment
import dev.fughetta.dshcompose.domain.terminal.TerminalFrame
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TerminalRepositoryTest {
    @Test
    fun attachmentHonorsInputWindowAndNeverReplaysAfterOutputSequenceLoss() = runBlocking {
        val inputFrames = Channel<JsonObject>(16)
        val uplink = Channel<JsonObject>(8)
        var attachId = ""
        var sockets = 0
        val delegate = HostConnection("https://unused.test")
        val link =
            MultiplexerLink(
                incoming = inputFrames,
                sendFrame = { frame ->
                    val id = frame.string("id").orEmpty()
                    when (frame.string("op")) {
                        "subscribe" ->
                            when (frame.string("method")) {
                                "\$events" ->
                                    inputFrames.trySend(
                                        buildJsonObject {
                                            put("op", "event")
                                            put("id", id)
                                            putJsonObject("data") { put("type", "ready") }
                                        },
                                    )
                                "terminal/attach" -> {
                                    attachId = id
                                    inputFrames.trySend(
                                        buildJsonObject {
                                            put("op", "event")
                                            put("id", id)
                                            put(
                                                "data",
                                                Json.parseToJsonElement(
                                                    """{"type":"snapshot","sequence":0,"screen":"","info":{"id":"pty","title":"shell","shell":{"path":"/bin/sh"},"state":"running"},"writable":true,"inputWindow":{"maxBytes":100,"frameOverheadBytes":8}}""",
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        "input" -> uplink.trySend(frame["data"]!!.jsonObject)
                    }
                    true
                },
                release = { inputFrames.close() },
            )
        val transport =
            object : HostTransport by delegate {
                override suspend fun openMuxLink(): MultiplexerLink {
                    sockets++
                    return link
                }
            }
        val client = ProtocolClient(transport)
        val repository = EngineTerminalRepository(client)
        val attachment =
            repository.attach(
                this,
                "session",
                "pty",
                TerminalEnvironment(maxInputBytes = 32, maxBufferedBytes = 4096),
            )
        val frames = Channel<TerminalFrame>(8)
        val failed = CompletableDeferred<Throwable>()
        val collector = launch {
            try {
                attachment.frames.collect { frames.send(it) }
            } catch (error: Throwable) {
                failed.complete(error)
            }
        }
        try {
            withTimeout(3000) {
                assertTrue(frames.receive() is TerminalFrame.Snapshot)
                assertTrue(attachment.writable)
                val input = "😀".repeat(20)
                assertTrue(attachment.write(input))
                val received = StringBuilder()
                repeat(3) { index ->
                    val command = uplink.receive()
                    assertEquals(index + 1L, command.long("sequence"))
                    val text = command.string("data")!!
                    assertTrue(text.toByteArray(Charsets.UTF_8).size <= 32)
                    received.append(text)
                    assertTrue(uplink.tryReceive().isFailure)
                    inputFrames.send(
                        buildJsonObject {
                            put("op", "event")
                            put("id", attachId)
                            putJsonObject("data") {
                                put("type", "ack")
                                put("sequence", index + 1)
                            }
                        },
                    )
                    assertTrue(frames.receive() is TerminalFrame.Ack)
                }
                assertEquals(input, received.toString())
                inputFrames.send(
                    buildJsonObject {
                        put("op", "event")
                        put("id", attachId)
                        putJsonObject("data") {
                            put("type", "output")
                            put("sequence", 2)
                            put("data", "gap")
                        }
                    },
                )
                assertTrue(failed.await() is HostProtocolException)
                assertFalse(attachment.writable)
                assertFalse(attachment.write("must not replay"))
                assertEquals(1, sockets)
            }
        } finally {
            attachment.close()
            collector.cancel()
            client.close()
        }
    }
}

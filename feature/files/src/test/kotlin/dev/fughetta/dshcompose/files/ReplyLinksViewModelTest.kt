package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.data.workspace.EngineWorkspaceFilesRepository
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.ReplyLinkUiState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReplyLinksViewModelTest {
    @Test
    fun clickedLinksUseTheirOwnWorkspaceAndCancelledLookupsCannotOpenFilesOrWebsites() =
        runBlocking {
            val slowStarted = CompletableDeferred<Unit>()
            val slowRelease = CompletableDeferred<Unit>()
            val slowFinished = CompletableDeferred<Unit>()
            val requested = Channel<Pair<String, String>>(Channel.UNLIMITED)
            val opened = Channel<Pair<String, String>>(Channel.UNLIMITED)
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                        install(WebSockets)
                        routing {
                            webSocket("/compose/events") {
                                val open =
                                    Json.parseToJsonElement(
                                            (incoming.receive() as Frame.Text).readText(),
                                        )
                                        .jsonObject
                                send(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", open.getValue("id"))
                                        putJsonObject("data") {
                                            put("type", "ready")
                                            put("clientId", "host")
                                            putJsonObject("host") { put("home", "/home") }
                                        }
                                    }
                                        .toString(),
                                )
                                awaitCancellation()
                            }
                            post("/compose/rpc") {
                                val input = Json.parseToJsonElement(call.receiveText()).jsonObject
                                val args = input.getValue("args").jsonObject
                                val session =
                                    args.getValue("workspaceFileScopeId").jsonPrimitive.content
                                val path = args.getValue("path").jsonPrimitive.content
                                requested.send(session to path)
                                if (path == "slow.com") {
                                    slowStarted.complete(Unit)
                                    withContext(NonCancellable) { slowRelease.await() }
                                }
                                call.respondText(
                                    buildJsonObject {
                                        put("id", input.getValue("id"))
                                        val code =
                                            when (path) {
                                                "missing.com" -> "workspace-file/not-found"
                                                "folder.com" -> "workspace-file/not-regular-file"
                                                else -> null
                                            }

                                        if (code != null)
                                            putJsonObject("error") {
                                                put("code", code)
                                                put("message", "Host file error")
                                            }
                                        else
                                            putJsonObject("result") {
                                                put("absolutePath", "/work/$path")
                                                put("version", "v1")
                                                put("bytes", 20)
                                            }
                                    }
                                        .toString(),
                                    ContentType.Application.Json,
                                )
                                if (path == "slow.com") slowFinished.complete(Unit)
                            }
                        }
                    }
                    .start(wait = false)
            val states = MutableStateFlow<ReplyLinkUiState?>(null)
            val pages = mutableListOf<String>()
            val controller =
                ReplyLinksViewModel(
                    this,
                    { it.toString() },
                    { states.value = it },
                    { session, path -> opened.trySend(session to path) },
                    { page -> pages.add(page) && page == "models" },
                )
            val port = server.engine.resolvedConnectors().single().port
            val client = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            try {
                withTimeout(10000) {
                    client.mux.ready()
                    controller.connect(EngineWorkspaceFilesRepository(client), 7)
                    controller.open("s1", 7, "docs/a%20b.md#heading")
                    assertEquals("s1" to "docs/a b.md", opened.receive())
                    assertTrue(requested.tryReceive().isFailure)
                    controller.open("s2", 7, "example.com")
                    assertEquals("s2" to "example.com", requested.receive())
                    assertEquals("s2" to "/work/example.com", opened.receive())
                    assertNull(states.value)
                    controller.open("s2", 7, "missing.com")
                    assertEquals("s2" to "missing.com", requested.receive())
                    val website = states.first { it?.external != null }!!
                    assertEquals("https://missing.com", website.external)
                    assertTrue(opened.tryReceive().isFailure)
                    controller.open("s2", 7, "folder.com")
                    assertEquals("s2" to "folder.com", requested.receive())
                    assertEquals("s2" to "folder.com", opened.receive())
                    controller.open("s1", 7, "slow.com")
                    slowStarted.await()
                    assertEquals("s1" to "slow.com", requested.receive())
                    controller.dismissSession("s1")
                    controller.open("s2", 7, "https://example.org")
                    val current = states.value!!
                    controller.dismiss(website.id)
                    assertEquals(current, states.value)
                    slowRelease.complete(Unit)
                    slowFinished.await()
                    assertEquals(current, states.value)
                    assertTrue(opened.tryReceive().isFailure)
                    controller.connect(null, 8)
                    controller.open("s1", 7, "https://stale.example.com")
                    assertNull(states.value)
                    controller.open("s2", 8, "javascript:alert(1)")
                    assertNull(states.value!!.external)
                    assertNotNull(states.value!!.error)
                    controller.open("s2", 8, "offline.com")
                    assertEquals("https://offline.com", states.value!!.external)
                    controller.open("s2", 8, "dsh://open/models")
                    assertNull(states.value)
                    controller.open("s2", 8, "dsh://open/nowhere")
                    assertNotNull(states.value!!.error)
                    assertEquals(listOf("models", "nowhere"), pages)
                    assertTrue(requested.tryReceive().isFailure)
                }
            } finally {
                slowRelease.complete(Unit)
                controller.close()
                client.close()
                server.stop(0, 1000)
            }
        }
}

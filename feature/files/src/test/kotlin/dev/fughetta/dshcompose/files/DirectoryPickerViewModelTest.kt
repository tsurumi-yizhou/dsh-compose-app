package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.DirectoryPickerState
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
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectoryPickerViewModelTest {
    @Test
    fun typedPathsSurviveLateReadsAndCreationUsesTheAcknowledgedFolder() = runBlocking {
        val staleStarted = CompletableDeferred<Unit>()
        val staleAck = CompletableDeferred<Unit>()
        val staleFinished = CompletableDeferred<Unit>()
        val sessionCreated = CompletableDeferred<String>()
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
                            val path = args["path"]?.jsonPrimitive?.content ?: "/home"
                            val value =
                                when (input.getValue("method").jsonPrimitive.content) {
                                    "directoryPicker/list" -> {
                                        if (path == "/slow") {
                                            staleStarted.complete(Unit)
                                            withContext(NonCancellable) { staleAck.await() }
                                        }
                                        buildJsonObject {
                                            put("path", path)
                                            put("home", "/home")
                                            putJsonArray("crumbs") {
                                                addJsonObject {
                                                    put("name", "/")
                                                    put("path", "/")
                                                }
                                                addJsonObject {
                                                    put("name", path)
                                                    put("path", path)
                                                }
                                            }
                                            putJsonArray("entries") {
                                                if (path != "/created") {
                                                    repeat(1000) {
                                                        addJsonObject {
                                                            put("name", "folder$it")
                                                            put("path", "/folder$it")
                                                        }
                                                    }
                                                    addJsonObject {
                                                        put("name", ".hidden-target")
                                                        put("path", "/hidden-target")
                                                        put("hidden", true)
                                                    }
                                                    addJsonObject {
                                                        put("name", "target")
                                                        put("path", "/target")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "directoryPicker/createDirectory" -> {
                                        assertEquals("/home", path)
                                        assertEquals(
                                            "New folder",
                                            args.getValue("name").jsonPrimitive.content,
                                        )
                                        JsonPrimitive("/created")
                                    }
                                    "session/create" -> {
                                        assertEquals(
                                            buildJsonObject { put("cwd", "/created") },
                                            args.getValue("request"),
                                        )
                                        buildJsonObject { put("sessionId", "new-session") }
                                    }
                                    else -> error("Unexpected directory operation")
                                }
                            call.respondText(
                                buildJsonObject {
                                    put("id", input.getValue("id"))

                                    put("result", value)
                                }
                                    .toString(),
                                ContentType.Application.Json,
                            )
                            if (path == "/slow") staleFinished.complete(Unit)
                        }
                    }
                }
                .start(wait = false)
        val states = MutableStateFlow<DirectoryPickerState?>(null)
        val controller =
            DirectoryPickerViewModel(
                this,
                { it.toString() },
                { states.value = it },
                { id -> sessionCreated.complete(id) },
            )
        val port = server.engine.resolvedConnectors().single().port
        val client = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
        try {
            withTimeout(10000) {
                client.mux.ready()
                controller.connect(
                    dev.fughetta.dshcompose.domain.workspaces.DirectoryPickerPorts(
                        dev.fughetta.dshcompose.data.workspace.EngineWorkspaceFilesRepository(
                            client,
                        ),
                        dev.fughetta.dshcompose.data.workspace.EngineWorkspacesRepository(
                            client,
                        ),
                        dev.fughetta.dshcompose.testing.ClientSessions(client),
                    ),
                )
                controller.open()
                states.first { it?.entries?.size == 1001 }
                controller.query("target")
                states.first { it?.entries?.map { entry -> entry.name } == listOf("target") }
                controller.hidden(true)
                states.first { it?.entries?.size == 2 }
                controller.browse("/slow")
                staleStarted.await()
                controller.editPath("/unsent-draft")
                assertFalse(states.value!!.canChoose)
                staleAck.complete(Unit)
                staleFinished.await()
                assertEquals("/unsent-draft", states.value!!.path)
                assertEquals("/home", states.value!!.level!!.path)
                controller.browse("/home")
                states.first { it?.loading == false && it.path == "/home" }
                controller.openCreate()
                controller.folderName("New folder")
                controller.create()
                states.first { it?.level?.path == "/created" && !it.loading }
                assertNull(states.value!!.newFolder)
                controller.choose()
                assertEquals("new-session", sessionCreated.await())
                assertNull(states.value)
            }
        } finally {
            staleAck.complete(Unit)
            controller.close()
            client.close()
            server.stop(0, 1000)
        }
    }
}

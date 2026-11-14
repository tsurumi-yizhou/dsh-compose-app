package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.data.workspace.EngineWorkspaceFilesRepository
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.WorkspaceFilesUiState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFilesViewModelTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun browserRetainsTheCurrentSearchAndFileVersionAcrossReadsAndWatchUpdates() = runBlocking {
        val version = AtomicInteger(1)
        val reads = AtomicInteger()
        val notifyChange = CompletableDeferred<Unit>()
        val watchesClosed = CompletableDeferred<Unit>()
        val closed = AtomicInteger()
        val oldSearchStarted = CompletableDeferred<Unit>()
        val oldSearchAck = CompletableDeferred<Unit>()
        val oldSearchFinished = CompletableDeferred<Unit>()
        val queries = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val conversions = AtomicInteger()
        val stats = AtomicInteger()
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
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val command = Json.parseToJsonElement(frame.readText()).jsonObject
                                if (command["op"]?.jsonPrimitive?.content == "cancel") {
                                    if (closed.incrementAndGet() == 2) watchesClosed.complete(Unit)
                                    continue
                                }
                                if (command["op"]?.jsonPrimitive?.content != "subscribe") continue
                                assertEquals(
                                    "workspaceFiles/changes",
                                    command.getValue("method").jsonPrimitive.content,
                                )
                                val path =
                                    command
                                        .getValue("args")
                                        .jsonObject
                                        .getValue("path")
                                        .jsonPrimitive
                                        .content
                                fun item(value: JsonObject) = buildJsonObject {
                                    put("op", "event")
                                    put("id", command.getValue("id"))
                                    put("data", value)
                                }
                                    .toString()
                                send(item(buildJsonObject { put("kind", "ready") }))
                                if (path == "long.txt")
                                    launch {
                                        notifyChange.await()
                                        send(
                                            item(
                                                buildJsonObject {
                                                    put("kind", "change")
                                                    putJsonObject("change") {
                                                        put("absolutePath", "/home/long.txt")
                                                        put("version", "v3")
                                                        put("absent", true)
                                                    }
                                                },
                                            ),
                                        )
                                    }
                            }
                        }
                        post("/compose/{path...}") {
                            val input = Json.parseToJsonElement(call.receiveText()).jsonObject
                            val fileRequest = input["payload"] != null
                            val args =
                                if (fileRequest)
                                    input.getValue("payload").jsonObject.getValue("args").jsonObject
                                else input.getValue("args").jsonObject
                            if (
                                input.getValue("method").jsonPrimitive.content ==
                                    "officeToPdf/render"
                            ) {
                                conversions.incrementAndGet()
                                val metadata = buildJsonObject {
                                    put("type", "server-response")
                                    put("rpcId", input.getValue("rpcId"))
                                    putJsonObject("result") {
                                        put("ok", true)
                                        putJsonObject("value") {
                                            put("data", JsonNull)
                                            putJsonArray("missingFonts") { add("Calibri") }
                                        }
                                    }

                                    putJsonArray("attachments") {
                                        addJsonObject {
                                            put("codec", "bytes")
                                            put("part", "pdf")
                                            putJsonArray("path") { add("data") }
                                        }
                                    }
                                }
                                call.respondText(
                                    "--office\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$metadata\r\n--office\r\nContent-Disposition: form-data; name=\"pdf\"\r\n\r\n%PDF-test\r\n--office--\r\n",
                                    ContentType.parse("multipart/form-data; boundary=office"),
                                )
                                return@post
                            }
                            val value =
                                when (input.getValue("method").jsonPrimitive.content) {
                                    "workspaceFiles/list" ->
                                        buildJsonObject {
                                            assertEquals(
                                                "s1",
                                                args
                                                    .getValue("workspaceFileScopeId")
                                                    .jsonPrimitive
                                                    .content,
                                            )
                                            put(
                                                "path",
                                                args
                                                    .getValue("path")
                                                    .jsonPrimitive
                                                    .content
                                                    .takeUnless { it == "." }
                                                    .orEmpty(),
                                            )
                                            putJsonArray("entries") {}
                                        }
                                    "workspaceFiles/stat" -> {
                                        stats.incrementAndGet()
                                        buildJsonObject {
                                            put("absolutePath", "/home/file")
                                            put("bytes", 3)
                                            put(
                                                "version",
                                                if (
                                                    args["path"]?.jsonPrimitive?.content ==
                                                        "report.docx"
                                                )
                                                    "office-v1"
                                                else "image-v1",
                                            )
                                        }
                                    }
                                    "workspaceFiles/readBytes" ->
                                        buildJsonObject {
                                            put("data", "aW1n")
                                            put("offset", 0)
                                            put("eof", true)
                                            put("version", "image-v1")
                                        }
                                    "workspaceFiles/read" -> {
                                        reads.incrementAndGet()
                                        val range = args.getValue("range").jsonObject
                                        val offset = range.getValue("offset").jsonPrimitive.int
                                        assertEquals(500, range.getValue("limit").jsonPrimitive.int)
                                        buildJsonObject {
                                            put("offset", offset)
                                            put("version", "v${version.get()}")
                                            put("eof", offset == 501)
                                            put("lines", if (offset == 1) 500 else 2)
                                            put(
                                                "text",
                                                if (offset == 1)
                                                    (1..500).joinToString("\n") { "line$it" }
                                                else "line501\nline502",
                                            )
                                        }
                                    }
                                    "fileReferences/list" -> {
                                        val query = args.getValue("query").jsonPrimitive.content
                                        queries.add(query)
                                        assertEquals(
                                            "s1",
                                            args.getValue("agentId").jsonPrimitive.content,
                                        )
                                        if (query == "old") {
                                            oldSearchStarted.complete(Unit)
                                            withContext(NonCancellable) { oldSearchAck.await() }
                                        }
                                        buildJsonArray {
                                            addJsonObject {
                                                put("path", "$query.txt")
                                                put("kind", "file")
                                            }
                                        }
                                    }
                                    else -> error("Unexpected file request")
                                }
                            try {
                                call.respondText(
                                    buildJsonObject {
                                        if (fileRequest) {
                                            put("type", "server-response")
                                            put("rpcId", input.getValue("rpcId"))
                                            putJsonObject("result") {
                                                put("ok", true)
                                                put("value", value)
                                            }
                                        } else {
                                            put("id", input.getValue("id"))
                                            put("result", value)
                                        }
                                    }
                                        .toString(),
                                    ContentType.Application.Json,
                                )
                            } finally {
                                if (args["query"]?.jsonPrimitive?.content == "old")
                                    oldSearchFinished.complete(Unit)
                            }
                        }
                    }
                }
                .start(wait = false)
        val states = MutableStateFlow<WorkspaceFilesUiState?>(null)
        val controller =
            WorkspaceFilesViewModel(this, { it.toString() }, previewDirectory = temporary.root) {
                states.value = it
            }
        val client =
            ProtocolClient(
                HostConnection(
                    "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}",
                ),
            )
        try {
            withTimeout(10000) {
                client.mux.ready()
                controller.connect(EngineWorkspaceFilesRepository(client))
                controller.open("s1", "docs/readme.txt")
                states.first {
                    it?.listing != null && it.preview?.page != null && it.preview?.loading == false
                }
                assertEquals("docs", states.value!!.path)
                assertEquals("docs/readme.txt", states.value!!.preview!!.path)
                assertEquals(500, states.value!!.preview!!.page!!.lines.size)
                controller.closePreview()
                controller.search("old", immediate = true)
                oldSearchStarted.await()
                controller.search("discarded")
                controller.search("current", immediate = true)
                states.first { it?.search?.query == "current" && !it.search.loading }
                oldSearchAck.complete(Unit)
                oldSearchFinished.await()
                assertEquals(listOf("current.txt"), states.value!!.search.results.map { it.path })
                assertEquals(listOf("old", "current"), queries.toList())
                controller.search("")
                assertTrue(states.value!!.search.results.isEmpty())
                controller.preview("long.txt")
                states.first { it?.preview?.page != null && it.preview?.loading == false }
                version.set(2)
                controller.page(true)
                states.first { it?.preview?.stale == true }
                assertEquals("v1", states.value!!.preview!!.page!!.version)
                assertEquals(500, states.value!!.preview!!.page!!.lines.size)
                controller.reloadPreview()
                states.first { it?.preview?.page?.version == "v2" && it.preview?.loading == false }
                assertTrue(states.value!!.preview!!.previous.isEmpty())
                controller.page(true)
                states.first { it?.preview?.page?.offset == 501 && it.preview?.loading == false }
                assertEquals(2, states.value!!.preview!!.page!!.lines.size)
                controller.page(false)
                states.first { it?.preview?.page?.offset == 1 && it.preview?.loading == false }
                assertTrue(states.value!!.preview!!.previous.isEmpty())
                val beforeWatch = reads.get()
                controller.observe(true)
                states.first { it?.preview?.loading == false && reads.get() > beforeWatch }
                notifyChange.complete(Unit)
                states.first { it?.preview?.stale == true }
                assertEquals("v2", states.value!!.preview!!.page!!.version)
                assertEquals(500, states.value!!.preview!!.page!!.lines.size)
                controller.observe(false)
                watchesClosed.await()
                assertNotNull(states.value!!.preview)
                controller.preview("photo.png")
                states.first { it?.preview?.cachePath != null && it.preview?.loading == false }
                val cached = File(states.value!!.preview!!.cachePath!!)
                assertEquals("img", cached.readText())
                assertEquals("image-v1", states.value!!.preview!!.fileVersion)
                controller.closePreview()
                assertFalse(cached.exists())
                controller.preview("report.docx")
                states.first { it?.preview?.cachePath != null && it.preview?.loading == false }
                val converted = File(states.value!!.preview!!.cachePath!!)
                assertEquals("%PDF-test", converted.readText())
                assertEquals(listOf("Calibri"), states.value!!.preview!!.missingFonts)
                assertEquals("office-v1", states.value!!.preview!!.fileVersion)
                val beforeVerify = stats.get()
                controller.observe(true)
                states.first { it?.preview?.loading == false && stats.get() > beforeVerify }
                assertEquals(1, conversions.get())
                controller.observe(false)
                controller.dismissSession("s1")
                assertNull(states.value)
                assertFalse(converted.exists())
            }
        } finally {
            oldSearchAck.complete(Unit)
            notifyChange.complete(Unit)
            controller.close()
            client.close()
            server.stop(0, 1000)
        }
    }
}

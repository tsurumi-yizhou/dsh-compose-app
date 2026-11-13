package dev.fughetta.dshcompose.data.workspace

import dev.fughetta.dshcompose.domain.workspaces.WorkspaceEvent
import dev.fughetta.dshcompose.protocol.*
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

class WorkspacesRepositoryTest {
    @Test
    fun streamSnapshotsAndWorkspaceMutationsKeepRegistrationSeparateFromSessionCreation() =
        runBlocking {
            val workspace =
                parseObject(
                    """{"workspaceId":"w1","path":"/project","title":"Project","sessionIds":["s1"],"updatedAt":"2026-11-07T00:00:00Z"}""",
                )
            val frames =
                listOf(
                    parseObject(
                        """{"type":"baseline","value":{"items":[$workspace],"archivedSessionIds":["s3"],"pinnedSessionIds":["s1"]}}""",
                    ),
                    parseObject("""{"type":"upsert","workspace":$workspace}"""),
                    parseObject("""{"type":"order","workspaceIds":["w1"]}"""),
                    parseObject("""{"type":"archived","archivedSessionIds":["s2"]}"""),
                    parseObject("""{"type":"pinned","pinnedSessionIds":[]}"""),
                    parseObject("""{"type":"remove","workspaceId":"w1"}"""),
                    parseObject(
                        """{"type":"replace","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":[]}}""",
                    ),
                )
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                        install(WebSockets)
                        routing {
                            webSocket("/compose/events") {
                                val events =
                                    parseObject((incoming.receive() as Frame.Text).readText())
                                fun item(id: JsonElement, value: JsonObject) = buildJsonObject {
                                    put("op", "event")
                                    put("id", id)
                                    put("data", value)
                                }
                                    .toString()
                                send(
                                    item(
                                        events.getValue("id"),
                                        parseObject(
                                            """{"type":"ready","clientId":"client1","host":{"home":"/home"}}""",
                                        ),
                                    ),
                                )
                                val follow =
                                    parseObject((incoming.receive() as Frame.Text).readText())
                                assertEquals("workspace/follow", follow.string("method"))
                                frames.forEach { send(item(follow.getValue("id"), it)) }
                                awaitCancellation()
                            }
                            post("/compose/rpc") {
                                val input = parseObject(call.receiveText())
                                val args =
                                    input
                                        .getValue("args")
                                        .objectValue()
                                        .getValue("request")
                                        .objectValue()
                                val value =
                                    when (input.string("method")) {
                                        "workspace/create" -> {
                                            assertEquals(
                                                parseObject("""{"path":"/project"}"""),
                                                args,
                                            )
                                            buildJsonObject {
                                                put("workspace", workspace)
                                                put("created", true)
                                            }
                                        }
                                        "workspace/rename" -> {
                                            assertEquals(
                                                parseObject(
                                                    """{"workspaceId":"w1","title":"Renamed"}""",
                                                ),
                                                args,
                                            )
                                            buildJsonObject { put("workspace", workspace) }
                                        }
                                        "workspace/delete" -> {
                                            assertEquals(
                                                parseObject("""{"workspaceId":"w1"}"""),
                                                args,
                                            )
                                            JsonObject(emptyMap())
                                        }
                                        "session/create" -> {
                                            assertEquals(
                                                parseObject("""{"workspaceId":"w1"}"""),
                                                args,
                                            )
                                            parseObject("""{"sessionId":"new-session"}""")
                                        }
                                        "workspace/insertBefore" -> {
                                            assertEquals(
                                                parseObject(
                                                    """{"workspaceId":"w1","beforeWorkspaceId":"w2"}""",
                                                ),
                                                args,
                                            )
                                            parseObject("""{"workspaceIds":["w1","w2"]}""")
                                        }
                                        "workspace/pinSession",
                                        "workspace/unpinSession" -> {
                                            assertEquals(
                                                parseObject("""{"sessionId":"s1"}"""),
                                                args,
                                            )
                                            if (input.string("method") == "workspace/pinSession")
                                                parseObject("""{"pinnedSessionIds":["s1","s2"]}""")
                                            else parseObject("""{"pinnedSessionIds":["s2"]}""")
                                        }
                                        "workspace/archiveSession" -> {
                                            assertEquals(
                                                parseObject(
                                                    """{"sessionId":"s1","stopActivity":true}""",
                                                ),
                                                args,
                                            )
                                            parseObject("""{"archivedSessionIds":["s1","s3"]}""")
                                        }
                                        "workspace/unarchiveSession" -> {
                                            assertEquals(
                                                parseObject("""{"sessionId":"s1"}"""),
                                                args,
                                            )
                                            parseObject("""{"archivedSessionIds":["s3"]}""")
                                        }
                                        else -> error("Unexpected workspace method")
                                    }
                                call.respondText(
                                    buildJsonObject {
                                        put("id", input.getValue("id"))

                                        put("result", value)
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
                    val updates =
                        withTimeout(5000) {
                            client.mux.ready()
                            EngineWorkspacesRepository(client).events().take(8).toList()
                        }
                    assertTrue(updates.first() is WorkspaceEvent.Reset)
                    val snapshot = updates[1] as WorkspaceEvent.Snapshot
                    assertEquals(setOf("s1"), snapshot.workspaces.single().sessionIds)
                    assertEquals(setOf("s3"), snapshot.archived)
                    assertEquals(listOf("s1"), snapshot.pinned)
                    assertTrue((updates.last() as WorkspaceEvent.Snapshot).workspaces.isEmpty())
                    assertEquals("w1", EngineWorkspacesRepository(client).create("/project").id)
                    assertEquals(
                        "w1",
                        EngineWorkspacesRepository(client).rename("w1", "Renamed").id,
                    )
                    assertEquals("new-session", client.createSession(workspaceId = "w1"))
                    assertEquals(
                        listOf("w1", "w2"),
                        EngineWorkspacesRepository(client).move("w1", "w2"),
                    )
                    assertEquals(
                        listOf("s1", "s2"),
                        EngineWorkspacesRepository(client).pin("s1", true),
                    )
                    assertEquals(listOf("s2"), EngineWorkspacesRepository(client).pin("s1", false))
                    assertEquals(
                        listOf("s1", "s3"),
                        EngineWorkspacesRepository(client).archive("s1", true),
                    )
                    assertEquals(listOf("s3"), EngineWorkspacesRepository(client).unarchive("s1"))
                    EngineWorkspacesRepository(client).delete("w1")
                }
            } finally {
                server.stop(0, 1000)
            }
        }
}

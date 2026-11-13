package dev.fughetta.dshcompose.data.workspace

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DirectoriesRepositoryTest {
    @Test
    fun choosingEngineFoldersUsesReturnedPathsAndPreservesEngineErrors() = runBlocking {
        var listings = 0
        var creations = 0
        var sessions = 0
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()
            var error: String? = null
            val value =
                when (input.string("method")) {
                    "directoryPicker/list" -> {
                        listings++
                        when (args.string("path")) {
                            null ->
                                parseObject(
                                    """{"path":"/home/engine","home":"/home/engine","crumbs":[{"name":"/","path":"/"},{"name":"home","path":"/home"},{"name":"engine","path":"/home/engine"}],"entries":[{"name":"Linked project","path":"/mnt/actual project"},{"name":".private","path":"/home/engine/.private","hidden":true}],"truncated":true}""",
                                )
                            "/missing" -> {
                                error = "directory-picker/unreadable"
                                JsonNull
                            }
                            else -> error("Unexpected list path")
                        }
                    }
                    "directoryPicker/createDirectory" -> {
                        creations++
                        assertEquals("/mnt/actual project", args.string("path"))
                        assertEquals("新项目", args.string("name"))
                        if (creations == 1) JsonPrimitive("/mnt/canonical/new-project")
                        else {
                            error = "directory-picker/exists"
                            JsonNull
                        }
                    }
                    "session/create" -> {
                        sessions++
                        assertEquals(
                            parseObject("""{"cwd":"/mnt/canonical/new-project"}"""),
                            args["request"],
                        )
                        parseObject("""{"sessionId":"created-session"}""")
                    }
                    else -> error("Unexpected directory request")
                }
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)
                    if (error == null) put("result", value)
                    else putJsonObject("error") { put("code", error) }
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val level = EngineWorkspaceFilesRepository(client).browse()
            assertEquals("/home", level.parent!!.path)
            assertTrue(level.truncated)
            assertFalse(level.entries.first().hidden)
            assertTrue(level.entries.last().hidden)
            val path =
                EngineWorkspaceFilesRepository(client)
                    .createDirectory(level.entries.first().path, "新项目")
            assertEquals("created-session", client.createSession(cwd = path))
            try {
                EngineWorkspaceFilesRepository(client).browse("/missing")
                fail()
            } catch (error: OperationFailure) {
                assertEquals("directory-picker/unreadable", error.code)
            }
            try {
                EngineWorkspaceFilesRepository(client)
                    .createDirectory(level.entries.first().path, "新项目")
                fail()
            } catch (error: OperationFailure) {
                assertEquals("directory-picker/exists", error.code)
            }
            assertEquals(2, listings)
            assertEquals(2, creations)
            assertEquals(1, sessions)
        }
    }
}

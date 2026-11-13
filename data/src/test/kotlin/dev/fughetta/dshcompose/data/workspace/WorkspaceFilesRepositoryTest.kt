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

class WorkspaceFilesRepositoryTest {
    @Test
    fun filesStaySessionScopedAndTextReadsUseBoundedLineWindows() = runBlocking {
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input.getValue("args").objectValue()
            assertEquals(
                "s1",
                args.string(
                    if (input.string("method") == "fileReferences/list") "agentId"
                    else "workspaceFileScopeId",
                ),
            )
            var error: String? = null
            val value =
                when (input.string("method")) {
                    "workspaceFiles/list" -> {
                        assertEquals(".", args.string("path"))
                        parseObject(
                            """{"path":"","entries":[{"name":"file10.txt","type":"file","size":100},{"name":"z-folder","type":"directory"},{"name":"file2.txt","type":"file","size":20}],"truncated":true}""",
                        )
                    }
                    "workspaceFiles/read" -> {
                        assertEquals(500L, args["range"]!!.objectValue().long("limit"))
                        if (args.string("path") == "binary") {
                            error = "workspace-file/not-text"
                            JsonNull
                        } else {
                            assertEquals("file2.txt", args.string("path"))
                            assertEquals(501L, args["range"]!!.objectValue().long("offset"))
                            parseObject(
                                """{"text":"first\nsecond","lines":2,"offset":501,"eof":true,"version":"opaque-version"}""",
                            )
                        }
                    }
                    "fileReferences/list" -> {
                        assertEquals("source/file", args.string("query"))
                        Json.parseToJsonElement(
                            """[{"path":"source/file2.txt","kind":"file"},{"path":"source/file10.txt","kind":"file"},{"path":"source/files","kind":"directory"},{"path":"","kind":"file"},{"kind":"file"}]""",
                        )
                    }
                    else -> error("Unexpected workspace file request")
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
            val listing = EngineWorkspaceFilesRepository(client).listing("s1")
            assertEquals(
                listOf("z-folder", "file2.txt", "file10.txt"),
                listing.entries.map { it.name },
            )
            assertTrue(listing.truncated)
            val page = EngineWorkspaceFilesRepository(client).readText("s1", "file2.txt", 501)
            assertEquals(listOf("first", "second"), page.lines)
            assertEquals(2, page.count)
            assertEquals(501, page.offset)
            assertEquals("opaque-version", page.version)
            assertTrue(page.eof)
            val results = EngineWorkspaceFilesRepository(client).search("s1", "source/file")
            assertEquals(
                listOf("source/file2.txt", "source/file10.txt", "source/files"),
                results.map { it.path },
            )
            assertFalse(results.first().isDirectory)
            assertTrue(results.last().isDirectory)
            try {
                EngineWorkspaceFilesRepository(client).readText("s1", "binary")
                fail()
            } catch (error: OperationFailure) {
                assertEquals("workspace-file/not-text", error.code)
            }
        }
    }
}

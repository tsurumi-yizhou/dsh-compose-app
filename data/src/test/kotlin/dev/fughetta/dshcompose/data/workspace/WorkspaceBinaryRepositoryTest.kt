package dev.fughetta.dshcompose.data.workspace

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceBinaryRepositoryTest {
    @Test
    fun multipartWindowsPreserveRawBytesAndStopBeforeWritingAChangedVersion() = runBlocking {
        val boundary = "binary-boundary"
        val bytes = byteArrayOf(0, 1, 2, -1) + "\r\n--${boundary}X\r\nend".toByteArray()
        val split = 4
        var changed = false
        val offsets = mutableListOf<Long>()
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val rpc = request.url.encodedPath == "/compose/rpc"
            val args =
                if (rpc) input.getValue("args").objectValue()
                else input.getValue("payload").objectValue().getValue("args").objectValue()
            assertEquals("s1", args.string("workspaceFileScopeId"))
            fun envelope(value: JsonObject, binary: Boolean = false) = buildJsonObject {
                if (rpc) {
                    put("id", input.getValue("id"))
                    put("result", value)
                    return@buildJsonObject
                }
                put("type", "server-response")
                put("rpcId", input.getValue("rpcId"))
                putJsonObject("result") {
                    put("ok", true)
                    put("value", value)
                }
                if (binary)
                    putJsonArray("attachments") {
                        addJsonObject {
                            put("codec", "bytes")
                            put("part", "chunk")
                            putJsonArray("path") { add("data") }
                        }
                    }
            }
            if (input.string("method") == "workspaceFiles/stat") {
                respond(
                    envelope(
                            buildJsonObject {
                                put("absolutePath", "/root/file")
                                put("version", "v1")
                                if (args.string("path") != "unknown")
                                    put(
                                        "bytes",
                                        if (args.string("path") == "empty") 0 else bytes.size,
                                    )
                            },
                        )
                        .toString(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                assertEquals("workspaceFiles/readBytes", input.string("method"))
                val range = args.getValue("options").objectValue().getValue("range").objectValue()
                val offset = range.requiredLong("offset")
                val length = range.requiredLong("length").toInt()
                if (args.string("path") != "unknown") assertEquals(1024 * 1024, length)
                if (args.string("path") == "empty") {
                    respond(
                        envelope(
                                buildJsonObject {
                                    put("data", "")
                                    put("offset", 0)
                                    put("eof", true)
                                    put("version", "v1")
                                },
                            )
                            .toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    offsets.add(offset)
                    val chunk =
                        bytes.copyOfRange(
                            offset.toInt(),
                            minOf(
                                bytes.size,
                                offset.toInt() + length,
                                if (offset == 0L) split else bytes.size,
                            ),
                        )
                    val metadata =
                        envelope(
                            buildJsonObject {
                                put("data", JsonNull)
                                put("offset", offset)
                                put("eof", offset + chunk.size == bytes.size.toLong())
                                put("version", if (changed && offset != 0L) "v2" else "v1")
                            },
                            binary = true,
                        )
                    val body =
                        ByteArrayOutputStream()
                            .apply {
                                write(
                                    "--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$metadata\r\n--$boundary\r\nContent-Disposition: form-data; name=\"chunk\"\r\n\r\n"
                                        .toByteArray(),
                                )
                                write(chunk)
                                write("\r\n--$boundary--\r\n".toByteArray())
                            }
                            .toByteArray()
                    respond(
                        body,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType,
                                "multipart/form-data; boundary=\"$boundary\"",
                            ),
                    )
                }
            }
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val output = ByteArrayOutputStream()
            assertEquals(
                bytes.size.toLong(),
                EngineWorkspaceFilesRepository(client).copy("s1", "file", output::write).bytes,
            )
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(listOf(0L, split.toLong()), offsets)
            changed = true
            val incomplete = ByteArrayOutputStream()
            try {
                EngineWorkspaceFilesRepository(client).copy("s1", "file", incomplete::write)
                fail()
            } catch (error: OperationFailure) {
                assertEquals("workspace-file/changed", error.code)
            }
            assertArrayEquals(bytes.copyOfRange(0, split), incomplete.toByteArray())
            val empty = ByteArrayOutputStream()
            assertEquals(
                0L,
                EngineWorkspaceFilesRepository(client).copy("s1", "empty", empty::write).bytes,
            )
            assertEquals(0, empty.size())
            val limited = ByteArrayOutputStream()
            try {
                EngineWorkspaceFilesRepository(client)
                    .copy("s1", "file", limited::write, maximumBytes = 2)
                fail()
            } catch (error: OperationFailure) {
                assertEquals("workspace-file/too-large", error.code)
            }
            assertEquals(0, limited.size())
            changed = false
            try {
                EngineWorkspaceFilesRepository(client)
                    .copy("s1", "unknown", limited::write, maximumBytes = split + 1L)
                fail()
            } catch (error: OperationFailure) {
                assertEquals("workspace-file/too-large", error.code)
            }
            assertEquals(split, limited.size())
        }
    }
}

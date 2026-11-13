package dev.fughetta.dshcompose.data.images

import dev.fughetta.dshcompose.domain.images.ReplyImageException
import dev.fughetta.dshcompose.domain.images.ReplyImageSource
import dev.fughetta.dshcompose.domain.images.replyImageSource
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReplyImagesTest {
    @Test
    fun imageSourcesResolveSessionPathsOnceAndStreamWebDataAndWorkspaceBytes() = runBlocking {
        assertEquals(
            "folder+a/%20.png",
            (replyImageSource("folder+a/%2520.png#preview") as ReplyImageSource.Workspace).path,
        )
        assertEquals(
            "/tmp/image one.png",
            (replyImageSource("file://localhost/tmp/image%20one.png") as ReplyImageSource.Workspace)
                .path,
        )
        assertEquals(
            "C:\\images\\one.png",
            (replyImageSource("C:\\images\\one.png") as ReplyImageSource.Workspace).path,
        )
        assertEquals(ReplyImageSource.Unsupported, replyImageSource("file://other/tmp/image.png"))
        assertEquals(ReplyImageSource.Unsupported, replyImageSource("javascript:alert(1)"))
        val bytes = byteArrayOf(1, 2, 3, 4)
        val compressed =
            ByteArrayOutputStream()
                .apply { GZIPOutputStream(this).use { it.write(bytes) } }
                .toByteArray()
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath == "/gzip.png")
                respond(
                    ByteReadChannel(compressed),
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("image/png"),
                        HttpHeaders.ContentEncoding to listOf("gzip"),
                    ),
                )
            else
                respond(
                    ByteReadChannel(bytes),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "image/png"),
                )
        }
        ReplyImageDownloads(engine, maximumBytes = 1024).use { images ->
            val web = ByteArrayOutputStream()
            assertEquals(
                "image/png",
                images.copy(replyImageSource("https://example.com/image.png"), web) { _, _, _ ->
                    fail("Not a workspace image")
                },
            )
            assertArrayEquals(bytes, web.toByteArray())
            val zipped = ByteArrayOutputStream()
            images.copy(replyImageSource("https://example.com/gzip.png"), zipped) { _, _, _ ->
                fail()
            }
            assertArrayEquals(bytes, zipped.toByteArray())
            val data = ByteArrayOutputStream()
            assertEquals(
                "image/png",
                images.copy(replyImageSource("data:image/png;base64,AQIDBA=="), data) { _, _, _ ->
                    fail()
                },
            )
            assertArrayEquals(bytes, data.toByteArray())
            val svg = ByteArrayOutputStream()
            images.copy(replyImageSource("data:image/svg+xml,%3Csvg%3E+图%3C/svg%3E"), svg) { _, _, _
                ->
                fail()
            }
            assertEquals("<svg>+图</svg>", svg.toString("UTF-8"))
            val workspace = ByteArrayOutputStream()
            images.copy(replyImageSource("chart%20one.png"), workspace) { path, output, limit ->
                assertEquals("chart one.png", path)
                assertEquals(1024L, limit)
                output.write(bytes)
            }
            assertArrayEquals(bytes, workspace.toByteArray())
        }
    }

    @Test
    fun actualTransferredBytesAreBoundedEvenWithoutAContentLengthAndInlineFailuresKeepStableCodes() =
        runBlocking {
            val engine = MockEngine { respond(ByteReadChannel(ByteArray(12)), HttpStatusCode.OK) }
            ReplyImageDownloads(engine, maximumBytes = 8).use { images ->
                for (address in
                    listOf(
                        "https://example.com/large.png",
                        "data:image/png;base64,AAAAAAAAAAAAAAAA",
                        "data:image/svg+xml,123456789",
                    )) {
                    val output = ByteArrayOutputStream()
                    try {
                        images.copy(replyImageSource(address), output) { _, _, _ -> fail() }
                        fail("Expected byte limit")
                    } catch (error: ReplyImageException) {
                        assertEquals("image/too-large", error.code)
                    }
                    assertTrue(output.size() <= 8)
                }
                try {
                    images.copy(
                        replyImageSource("data:image/png;base64,invalid!"),
                        ByteArrayOutputStream(),
                    ) { _, _, _ ->
                        fail()
                    }
                    fail()
                } catch (error: ReplyImageException) {
                    assertEquals("image/invalid-data", error.code)
                }
            }
        }
}

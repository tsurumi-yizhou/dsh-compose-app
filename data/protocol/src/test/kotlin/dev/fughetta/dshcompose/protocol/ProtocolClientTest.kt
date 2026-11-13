package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.attachments.AttachmentReference
import dev.fughetta.dshcompose.domain.messages.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolClientTest {
    @Test
    fun pairingPreservesTheServiceFailureCodeOverTailnetHttp() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"error":{"code":"pairing/expired"}}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val connection = HostConnection("http://desktop.tail123.ts.net:3095", engine = engine)
        try {
            connection.login("12345678")
            fail("Expired pairing must fail")
        } catch (error: HostRemoteException) {
            assertEquals("pairing/expired", error.code)
        } finally {
            connection.close()
        }
    }

    @Test
    fun conversationMutationsUseHostConfirmedValuesWithoutReadingTheInventory() = runBlocking {
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            if (request.url.encodedPath == "/compose/extensions/v1/sessions/delete") {
                assertEquals("s1", input.string("sessionId"))
                methods += "delete"
                respond(
                    """{"deleted":true,"sessionId":"s1"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                val method = input.requiredString("method")
                methods += method
                val args = input["args"]!!.objectValue()["request"]!!.objectValue()
                assertEquals("s1", args.string("sessionId"))
                val value =
                    when (method) {
                        "session/rename" -> {
                            assertEquals("requested title", args.string("title"))
                            buildJsonObject { put("title", "Host-confirmed title") }
                        }
                        "session/fork" -> {
                            assertNull(args["atSeq"])
                            buildJsonObject { put("sessionId", "fork1") }
                        }
                        else -> error("Unexpected inventory read or replay")
                    }
                respond(
                    buildJsonObject {
                        put("id", input["id"]!!)

                        put("result", value)
                    }
                        .toString(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            assertEquals("Host-confirmed title", client.renameSession("s1", "requested title"))
            assertEquals("fork1", client.forkSession("s1"))
            client.deleteSessionPermanently("s1")
        }
        assertEquals(listOf("session/rename", "session/fork", "delete"), methods)
    }

    @Test
    fun largeInventoryReadsSummariesWithoutOpeningSessionHistories() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("/compose/rpc", request.url.encodedPath)
            val input = parseObject((request.body as TextContent).text)
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)

                    putJsonObject("result") {
                        putJsonArray("items") {
                            repeat(20_000) { index ->
                                addJsonObject {
                                    put("sessionId", "s$index")
                                    put("updatedAt", 20_000 - index)
                                    putJsonObject("projections") {
                                        putJsonObject("values") {
                                            put("title", "Conversation $index")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val sessions = client.listSessions()
            assertEquals(20_000, sessions.size)
            assertEquals("s0", sessions.first().sessionId)
            assertEquals("Conversation 19999", sessions.last().title)
            assertEquals(1, calls)
        }
    }

    @Test
    fun imageReadSendsTheCompleteDurableReference() = runBlocking {
        val reference = buildJsonObject {
            put("attachmentId", "image1")
            put("mediaType", "image/png")
            put("width", 640)
            put("height", 480)
        }
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()["request"]!!.objectValue()
            assertEquals("s1", args.string("sessionId"))
            assertEquals(reference, args["attachment"])
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)

                    putJsonObject("result") { put("data", "AQID") }
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                client.readImage("s1", AttachmentReference(reference)),
            )
        }
    }

    @Test
    fun pairingAndSessionOperationsUseDeviceAuthorizationAndWireArguments() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("service.example", request.headers[HttpHeaders.Host])
            assertNull(request.headers[HttpHeaders.Origin])
            calls += request.url.encodedPath
            if (request.url.encodedPath == "/mounted/compose/pair") {
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.url.parameters["token"])
                val pairing = parseObject((request.body as TextContent).text)
                assertEquals("secret code", pairing.string("code"))
                assertEquals("Android", pairing.string("name"))
                respond(
                    """{"deviceId":"device-one","credential":"device-one.secret"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                assertEquals("Bearer device-one.secret", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.url.parameters["token"])
                val input = parseObject((request.body as TextContent).text)
                assertTrue(input.requiredString("id").isNotBlank())
                assertNotNull(input.long("sentAt"))
                val args = input["args"]!!.objectValue()
                val value =
                    when (input.string("method")) {
                        "session/list" -> {
                            assertEquals(buildJsonObject { putJsonObject("_request") {} }, args)
                            buildJsonObject {
                                putJsonArray("items") {
                                    addJsonObject {
                                        put("sessionId", "s1")
                                        put("updatedAt", 1234)
                                        put("running", true)
                                        putJsonObject("projections") {
                                            put("asOfSeq", 12)
                                            putJsonObject("values") { put("title", "Conversation") }
                                        }
                                    }
                                }
                            }
                        }
                        "session/prompt" -> {
                            val prompt = args["request"]!!.objectValue()
                            assertEquals("s1", prompt.string("sessionId"))
                            assertEquals("stable-id", prompt.string("requestId"))
                            assertEquals("steer", prompt.string("mode"))
                            assertEquals("Asia/Taipei", prompt.string("clientTimeZone"))
                            assertEquals(
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "hello")
                                        },
                                    ),
                                ),
                                prompt["content"],
                            )
                            JsonNull
                        }
                        else -> error("Unexpected endpoint")
                    }
                respond(
                    buildJsonObject {
                        put("id", input["id"]!!)

                        put("result", value)
                    }
                        .toString(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val local =
            HostConnection(
                "https://service.example/mounted",
                engine = engine,
            )
        ProtocolClient(local).use { client ->
            val grant = local.login("secret code")
            assertFalse(grant.toString().contains("secret"))
            val session = client.listSessions().single()
            assertEquals("Conversation", session.title)
            assertTrue(session.running)
            assertEquals(1234L, session.updatedAt)
            assertEquals(
                "stable-id",
                client.prompt(
                    "s1",
                    "hello",
                    PromptMode.Steer,
                    "stable-id",
                    timeZone = "Asia/Taipei",
                ),
            )
        }
        assertEquals(
            listOf("/mounted/compose/pair", "/mounted/compose/rpc", "/mounted/compose/rpc"),
            calls,
        )
    }

    @Test
    fun deletionRequiresTheHostToConfirmExactlyTheRequestedSession() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("/compose/extensions/v1/sessions/delete", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("s1", parseObject((request.body as TextContent).text).string("sessionId"))
            when (requests) {
                1 ->
                    respond(
                        "{\"deleted\":true,\"sessionId\":\"other\"}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                2 ->
                    respond(
                        "{\"error\":{\"code\":\"session-running\"}}",
                        HttpStatusCode.Conflict,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                3 ->
                    respond(
                        "",
                        HttpStatusCode.TemporaryRedirect,
                        headersOf(HttpHeaders.Location, "/unexpected"),
                    )
                else -> error("Deletion was replayed")
            }
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            try {
                client.deleteSessionPermanently("s1")
                fail()
            } catch (_: HostProtocolException) {}
            try {
                client.deleteSessionPermanently("s1")
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("session-running", error.code)
            }
            try {
                client.deleteSessionPermanently("s1")
                fail()
            } catch (error: HostHttpException) {
                assertEquals(307, error.status)
            }
        }
        assertEquals(3, requests)
    }

    @Test
    fun droppedMutationIsNotRetriedAndItsExceptionContainsNoCredential() = runBlocking {
        val count = AtomicInteger()
        val engine = MockEngine {
            count.incrementAndGet()
            throw IOException("Network error at https://host/?token=private")
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            try {
                client.createSession()
                fail()
            } catch (error: HostTransportException) {
                assertFalse(error.stackTraceToString().contains("private"))
            }
        }
        assertEquals(1, count.get())
    }

    @Test
    fun cancellingRequestCancelsKtorWork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val job = launch { client.listSessions() }
            withTimeout(5000) { started.await() }
            job.cancelAndJoin()
            withTimeout(5000) { finished.await() }
        }
    }

    @Test
    fun rpcRejectsMismatchedSuccessIdsButPreservesGatewayFailureCodes() = runBlocking {
        var mismatch = false
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            respond(
                buildJsonObject {
                    put("id", if (mismatch) JsonPrimitive("wrong") else input["id"]!!)
                    if (mismatch) put("result", JsonNull)
                    else putJsonObject("error") { put("code", "gateway/arguments-invalid") }
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        HostConnection("http://localhost:3080", engine = engine).use { connection ->
            try {
                connection.call("session/list")
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("gateway/arguments-invalid", error.code)
            }
            mismatch = true
            try {
                connection.call("session/list")
                fail()
            } catch (_: HostProtocolException) {}
        }
    }
}

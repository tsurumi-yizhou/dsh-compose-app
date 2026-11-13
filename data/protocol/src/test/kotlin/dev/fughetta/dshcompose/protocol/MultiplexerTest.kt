package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MultiplexerTest {
    @Test
    fun approvalsRejectRetiredGenerationsAndSettleOnlyAfterTheHostAcknowledges() = runBlocking {
        val rpcStarted = CompletableDeferred<Unit>()
        val acknowledge = CompletableDeferred<Unit>()
        val rpcCount = AtomicInteger()
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        post("/compose/rpc") {
                            rpcCount.incrementAndGet()
                            val input = parseObject(call.receiveText())
                            val args = input["args"]!!.objectValue()
                            check(
                                args.string("clientId") == "client1" &&
                                    args.string("eventId") == "event1",
                            )
                            check(args["outcome"]!!.objectValue().string("value") == "allowed-once")
                            rpcStarted.complete(Unit)
                            acknowledge.await()
                            call.respondText(
                                buildJsonObject {
                                    put("id", input["id"]!!)
                                    put("result", JsonNull)
                                }
                                    .toString(),
                                ContentType.Application.Json,
                            )
                        }
                        webSocket("/compose/events") {
                            val events = parseObject((incoming.receive() as Frame.Text).readText())
                            fun frame(value: JsonObject) = buildJsonObject {
                                put("op", "event")
                                put("id", events["id"]!!)
                                put("data", value)
                            }
                                .toString()
                            send(
                                frame(
                                    buildJsonObject {
                                        put("type", "ready")
                                        put("clientId", "client1")
                                        putJsonObject("host") { put("home", "/root") }
                                    },
                                ),
                            )
                            send(
                                frame(
                                    buildJsonObject {
                                        put("type", "waterfall")
                                        put("event", "approval/request")
                                        put("eventId", "event1")
                                        put("agentId", "s1")
                                        putJsonObject("request") {
                                            put("toolName", "readFile")
                                            put("callId", "call1")
                                        }
                                    },
                                ),
                            )
                            awaitCancellation()
                        }
                    }
                }
                .start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            ProtocolClient(HostConnection("http://127.0.0.1:$port")).use { client ->
                val generation = client.mux.ready()
                withTimeout(5000) { client.mux.pendingRequests.first { "event1" in it } }
                val outcome = buildJsonObject {
                    put("kind", "result")
                    put("value", "allowed-once")
                }
                try {
                    client.mux.respondHostEvent(
                        "event1",
                        outcome,
                        generation.copy(number = generation.number + 1),
                    )
                    fail()
                } catch (error: HostRemoteException) {
                    assertEquals("request/stale", error.code)
                }
                assertEquals(0, rpcCount.get())
                val reply = async { client.mux.respondHostEvent("event1", outcome, generation) }
                withTimeout(5000) { rpcStarted.await() }
                assertTrue("event1" in client.mux.pendingRequests.value)
                acknowledge.complete(Unit)
                withTimeout(5000) { reply.await() }
                assertFalse("event1" in client.mux.pendingRequests.value)
                assertEquals(1, rpcCount.get())
            }
        } finally {
            acknowledge.complete(Unit)
            server.stop(0, 1000)
        }
    }

    @Test
    fun followGapReopensSnapshotAndRepairsMissingHistory() = runBlocking {
        val follows = AtomicInteger()
        val pages = AtomicInteger()
        fun record(seq: Int) = buildJsonObject {
            putJsonObject("event") {
                put("seq", seq)
                put("time", 1000 + seq)
                put("type", "user/message")
                putJsonObject("data") { put("id", "m$seq") }
            }
        }
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        post("/compose/rpc") {
                            pages.incrementAndGet()
                            val input = parseObject(call.receiveText())
                            val request = input["args"]!!.objectValue()["request"]!!.objectValue()
                            check(
                                request.long("throughSeq") == 3L && request.long("beforeSeq") == 3L,
                            )
                            check(request.long("maxMessages") == 100L)
                            check(request["address"]!!.objectValue().string("kind") == "session")
                            call.respondText(
                                buildJsonObject {
                                    put("id", input["id"]!!)

                                    putJsonObject("result") {
                                        put("records", JsonArray(listOf(record(1), record(2))))
                                        put("hasMore", true)
                                    }
                                }
                                    .toString(),
                                ContentType.Application.Json,
                            )
                        }
                        webSocket("/compose/events") {
                            val events = parseObject((incoming.receive() as Frame.Text).readText())
                            send(
                                buildJsonObject {
                                    put("op", "event")
                                    put("id", events["id"]!!)
                                    putJsonObject("data") {
                                        put("type", "ready")
                                        put("clientId", "client")
                                        putJsonObject("host") { put("home", "/root") }
                                    }
                                }
                                    .toString(),
                            )
                            for (raw in incoming) {
                                val open = parseObject((raw as Frame.Text).readText())
                                if (open.string("op") != "subscribe") continue
                                val first = follows.incrementAndGet() == 1
                                send(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", open["id"]!!)
                                        putJsonObject("data") {
                                            put("type", "snapshot")
                                            put("cursor", if (first) 0 else 3)
                                            put(
                                                "records",
                                                JsonArray(listOf(record(if (first) 0 else 3))),
                                            )
                                            put("hasMore", !first)
                                            putJsonObject("header") { put("id", "s1") }
                                            putJsonObject("projections") {}
                                        }
                                    }
                                        .toString(),
                                )
                                if (first)
                                    send(
                                        buildJsonObject {
                                            put("op", "event")
                                            put("id", open["id"]!!)
                                            putJsonObject("data") {
                                                put("type", "event")
                                                put("event", record(2)["event"]!!)
                                            }
                                        }
                                            .toString(),
                                    )
                            }
                        }
                    }
                }
                .start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            ProtocolClient(HostConnection("http://127.0.0.1:$port")).use { client ->
                val updates = withTimeout(10_000) { client.watchSession("s1").take(4).toList() }
                val snapshots = updates.filterIsInstance<SessionUpdate.Snapshot>()
                assertEquals(listOf(0L), snapshots[0].records.map { it.seq })
                assertEquals(listOf(1L, 2L, 3L), snapshots[1].records.map { it.seq })
                assertEquals(3L, snapshots[1].cursor)
                assertEquals(2, follows.get())
                assertEquals(1, pages.get())
            }
        } finally {
            server.stop(0, 1000)
        }
    }

    @Test
    fun reconnectReopensFollowAndCancellationStopsOnlyItsLogicalStream() = runBlocking {
        val connections = AtomicInteger()
        val cancelled = CompletableDeferred<Unit>()
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(WebSockets)
                    routing {
                        webSocket("/compose/events") {
                            val generation = connections.incrementAndGet()
                            val events = parseObject((incoming.receive() as Frame.Text).readText())
                            check(events.string("method") == "\$events")
                            val eventsId = events.requiredString("id")
                            send(
                                buildJsonObject {
                                    put("op", "event")
                                    put("id", eventsId)
                                    putJsonObject("data") {
                                        put("type", "ready")
                                        put("clientId", "client-$generation")
                                        putJsonObject("host") { put("home", "/root") }
                                    }
                                }
                                    .toString(),
                            )
                            val follow = parseObject((incoming.receive() as Frame.Text).readText())
                            check(follow.string("method") == "session/follow")
                            val streamId = follow.requiredString("id")
                            send(
                                buildJsonObject {
                                    put("op", "event")
                                    put("id", streamId)
                                    putJsonObject("data") {
                                        put("type", "snapshot")
                                        put("generation", generation)
                                    }
                                }
                                    .toString(),
                            )
                            if (generation == 1)
                                close(CloseReason(CloseReason.Codes.GOING_AWAY, "restart"))
                            else {
                                val frame =
                                    parseObject((incoming.receive() as Frame.Text).readText())
                                check(
                                    frame.string("op") == "cancel" &&
                                        frame.string("id") == streamId,
                                )
                                cancelled.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    }
                }
                .start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            ProtocolClient(HostConnection("http://127.0.0.1:$port")).use { client ->
                val events =
                    withTimeout(10_000) { client.mux.stream("session/follow").take(4).toList() }
                assertEquals(
                    listOf(1L, 2L),
                    events.filterIsInstance<MuxEvent.Reset>().map { it.generation.number },
                )
                assertEquals(
                    listOf(1, 2),
                    events.filterIsInstance<MuxEvent.Item>().map {
                        it.value.objectValue()["generation"]!!.jsonPrimitive.int
                    },
                )
                withTimeout(5000) { cancelled.await() }
                assertEquals(ConnectionState.Connected, client.mux.state.value.state)
            }
        } finally {
            server.stop(0, 1000)
        }
    }

    @Test
    fun rejectedAuthenticationStopsReconnectUntilExplicitLogin() = runBlocking {
        val requests = AtomicInteger()
        val server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    routing {
                        get("/compose/events") {
                            requests.incrementAndGet()
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                }
                .start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            ProtocolClient(HostConnection("http://127.0.0.1:$port")).use { client ->
                try {
                    client.mux.ready(5000)
                    fail()
                } catch (error: HostHttpException) {
                    assertEquals(401, error.status)
                }
                assertEquals(ConnectionState.Unauthorized, client.mux.state.value.state)
                assertEquals(1, requests.get())
            }
        } finally {
            server.stop(0, 1000)
        }
    }
}

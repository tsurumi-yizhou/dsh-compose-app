package dev.fughetta.dshcompose.data.notify

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.notify.NotificationSessionCallbacks
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NotificationSessionsTest {
    private class Host(val id: String) {
        val reading = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<JsonElement>()
        private val delegate = HostConnection("https://unused.test")
        val client =
            ProtocolClient(
                object : HostTransport by delegate {
                    override suspend fun call(endpoint: String, args: JsonObject): JsonElement {
                        check(endpoint == "session/list")
                        reading.complete(Unit)
                        return withContext(NonCancellable) { reply.await() }
                    }

                    override suspend fun openMuxLink(): MultiplexerLink {
                        val input = Channel<JsonObject>(Channel.UNLIMITED)
                        return MultiplexerLink(
                            input,
                            sendFrame = { frame ->
                                input.trySend(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", frame.getValue("id"))
                                        putJsonObject("data") {
                                            put("type", "ready")
                                            put("clientId", id)
                                            putJsonObject("host") { put("home", "/home/test") }
                                        }
                                    },
                                )
                                true
                            },
                            release = { input.close() },
                        )
                    }
                },
            )
    }

    @Test
    fun reconnectAndLateSnapshotsPreserveNewerIdleEvents() = runBlocking {
        withTimeout(5_000) {
            val old = Host("old")
            val fresh = Host("fresh")
            val ended = mutableListOf<Pair<String, ConnectionGeneration>>()
            val sessions =
                EngineNotificationsRepository(
                    this,
                    NotificationSessionCallbacks(
                        selected = { true },
                        needed = { emptySet() },
                        onEnd = { id, _, generation -> ended += id to generation },
                        onRunning = {},
                        onRemoved = {},
                        onChanged = {},
                        onError = { error(it) },
                    ),
                )
            fun status(active: Boolean) = buildJsonObject {
                put("type", "emit")
                put("event", "api-session/status")
                putJsonArray("args") {
                    add("live")
                    add(active)
                }
            }
            fun snapshot(id: String) = buildJsonObject {
                putJsonArray("items") {
                    addJsonObject {
                        put("sessionId", id)
                        put("running", true)
                    }
                }
            }
            try {
                old.client.mux.ready()
                sessions.connect(EngineNotificationsRepository(old.client))
                old.reading.await()
                val oldRead = sessions.bootstrap!!
                sessions.event(
                    status(true).toToolPayload()
                        as dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
                )
                val generation = fresh.client.mux.ready()
                sessions.connect(EngineNotificationsRepository(fresh.client))
                fresh.reading.await()
                sessions.event(
                    status(false).toToolPayload()
                        as dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
                )
                fresh.reply.complete(snapshot("live"))
                sessions.bootstrap!!.join()
                old.reply.complete(snapshot("obsolete"))
                oldRead.join()
                assertTrue(sessions.seeded)
                assertTrue(sessions.running.isEmpty())
                assertEquals(listOf("live" to generation), ended)
                assertEquals(generation, sessions.generation)
            } finally {
                old.reply.complete(snapshot("obsolete"))
                fresh.reply.complete(snapshot("live"))
                sessions.close()
                old.client.close()
                fresh.client.close()
            }
        }
    }
}

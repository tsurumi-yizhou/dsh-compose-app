package dev.fughetta.dshcompose.data.testing

import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*

/** In-memory host transport; repositories use the real client and mux lifecycle. */
internal class ProtocolHost(
    private val id: String = "host",
    private val home: String = "/home/$id",
    private val goalRead: Boolean = true,
    private val upload:
        suspend (String, java.io.File, String, suspend (Long, Long?) -> Unit) -> FileReceipt =
        { _, _, _, _ ->
            error("Unexpected upload")
        },
    private val deleteSession: suspend (String) -> Unit = { error("Unexpected delete") },
    private val openStream: (String, (JsonObject) -> Unit) -> Unit = { endpoint, _ ->
        error("Unexpected stream: $endpoint")
    },
    private val request: suspend (String, JsonObject) -> JsonElement,
) : AutoCloseable {
    private var sendEvent: ((JsonObject) -> Unit)? = null

    suspend fun emit(event: JsonObject) = checkNotNull(sendEvent)(event)

    private val delegate = HostConnection("https://unused.test")
    val client =
        ProtocolClient(
            object : HostTransport by delegate {
                override val supportsGoalRead
                    get() = goalRead

                override suspend fun call(endpoint: String, args: JsonObject) =
                    request(endpoint, args)

                override suspend fun callWithTimeout(
                    endpoint: String,
                    args: JsonObject,
                    timeoutMillis: Long,
                ) = request(endpoint, args)

                override suspend fun uploadFile(
                    sessionId: String,
                    file: java.io.File,
                    name: String,
                    onProgress: suspend (Long, Long?) -> Unit,
                ) = upload(sessionId, file, name, onProgress)

                override suspend fun deleteSessionPermanently(sessionId: String) =
                    deleteSession(sessionId)

                override suspend fun openMuxLink(): MultiplexerLink {
                    val input = Channel<JsonObject>(Channel.UNLIMITED)
                    return MultiplexerLink(
                        input,
                        sendFrame = { frame ->
                            if (frame["op"] == JsonPrimitive("subscribe")) {
                                val send: (JsonObject) -> Unit = { event ->
                                    input.trySend(
                                        buildJsonObject {
                                            put("op", "event")
                                            put("id", frame.getValue("id"))
                                            put("data", event)
                                        },
                                    )
                                }
                                val endpoint = frame.getValue("method").jsonPrimitive.content
                                if (endpoint == "\$events") {
                                    sendEvent = send
                                    send(
                                        buildJsonObject {
                                            put("type", "ready")
                                            put("clientId", id)
                                            putJsonObject("host") { put("home", home) }
                                        },
                                    )
                                } else openStream(endpoint, send)
                            }
                            true
                        },
                        release = { input.close() },
                    )
                }
            },
        )

    override fun close() = client.close()
}

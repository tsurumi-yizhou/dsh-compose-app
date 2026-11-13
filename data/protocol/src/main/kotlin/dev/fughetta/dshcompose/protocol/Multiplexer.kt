package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.hosts.ConnectionStatus
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

sealed interface MuxEvent {
    /** A new physical connection; consumers must replace their old session snapshot. */
    data class Reset(val generation: ConnectionGeneration) : MuxEvent

    data class Item(val value: JsonElement) : MuxEvent
}

private class LinkLost : IOException("Mux link lost")

/**
 * One physical socket, isolated logical streams, and a fresh ready handshake on every reconnect.
 */
class Multiplexer(private val connection: HostTransport) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutableState = MutableStateFlow(ConnectionStatus(ConnectionState.Idle))
    val state: StateFlow<ConnectionStatus> = mutableState.asStateFlow()
    private val mutableHostEvents = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val hostEvents: SharedFlow<JsonObject> = mutableHostEvents.asSharedFlow()
    private val mutablePending = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val pendingRequests: StateFlow<Map<String, JsonObject>> = mutablePending.asStateFlow()
    private val streams = mutableMapOf<String, Channel<JsonElement>>()
    private var socket: MultiplexerLink? = null
    private var runner: Job? = null
    private var closed = false
    private var runnerNumber = 0L
    private var generationNumber = 0L

    suspend fun ready(timeoutMillis: Long = 20_000): ConnectionGeneration {
        start()
        val result =
            withTimeout(timeoutMillis) {
                state.first {
                    it.state in
                        setOf(
                            ConnectionState.Connected,
                            ConnectionState.Unauthorized,
                            ConnectionState.Closed,
                        )
                }
            }
        return result.generation ?: throw (result.error ?: ConnectionClosedException())
    }

    private fun start() =
        synchronized(lock) {
            if (closed) throw ConnectionClosedException()
            if (runner == null) {
                val number = ++runnerNumber
                runner =
                    scope
                        .launch(start = CoroutineStart.LAZY) { runConnections(number) }
                        .also { it.start() }
            }
        }

    /** Call after refreshing authentication; failed mutations are never replayed. */
    fun reconnect() =
        synchronized(lock) {
            if (closed) return@synchronized
            socket?.close()
            socket = null
            streams.values.forEach { it.close(LinkLost()) }
            streams.clear()
            mutablePending.value = emptyMap()
            mutableState.value = ConnectionStatus(ConnectionState.Reconnecting)
            runner?.cancel()
            val number = ++runnerNumber
            runner =
                scope
                    .launch(start = CoroutineStart.LAZY) { runConnections(number) }
                    .also { it.start() }
        }

    /** Each collector owns a logical stream. A reconnect reopens it from a fresh Host snapshot. */
    fun stream(endpoint: String, args: JsonObject = JsonObject(emptyMap())): Flow<MuxEvent> = flow {
        checkEndpoint(endpoint)
        while (currentCoroutineContext().isActive) {
            val generation = ready(timeoutMillis = Long.MAX_VALUE)
            val id = UUID.randomUUID().toString()
            val channel = Channel<JsonElement>(64)
            val opened =
                synchronized(lock) {
                    if (mutableState.value.generation != generation) false
                    else {
                        streams[id] = channel
                        if (!sendOpen(socket, id, endpoint, args)) {
                            streams.remove(id)
                            socket?.close()
                            false
                        } else true
                    }
                }
            if (!opened) {
                channel.close()
                yield()
                continue
            }
            try {
                emit(MuxEvent.Reset(generation))
                for (value in channel) emit(MuxEvent.Item(value))
                return@flow
            } catch (_: LinkLost) {
                // Reopen only read streams, with a new stream id and a reset event.
            } finally {
                synchronized(lock) {
                    if (streams.remove(id) != null && mutableState.value.generation == generation) {
                        send(
                            socket,
                            buildJsonObject {
                                put("op", "cancel")
                                put("id", id)
                            },
                        )
                    }
                }
                channel.cancel()
            }
        }
    }

    suspend fun respondHostEvent(
        eventId: String,
        outcome: JsonObject,
        expectedGeneration: ConnectionGeneration? = null,
    ) {
        val generation = ready()
        if (expectedGeneration != null && generation != expectedGeneration)
            throw HostRemoteException("request/stale")
        connection.call(
            "\$events/result",
            buildJsonObject {
                put("clientId", generation.clientId)
                put("eventId", eventId)
                put("outcome", outcome)
            },
        )
        synchronized(lock) {
            if (state.value.generation == generation)
                mutablePending.value = mutablePending.value - eventId
        }
    }

    private suspend fun runConnections(number: Long) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            synchronized(lock) {
                if (closed || runnerNumber != number) return
                mutableState.value =
                    ConnectionStatus(
                        if (attempt == 0) ConnectionState.Connecting
                        else ConnectionState.Reconnecting,
                    )
            }
            var link: MultiplexerLink? = null
            var failure: Throwable = LinkLost()
            try {
                val eventsId = UUID.randomUUID().toString()
                withTimeout(connection.connectTimeoutMillis) {
                    val session = connection.openMuxLink()
                    link = session
                    synchronized(lock) {
                        if (closed || runnerNumber != number) throw ConnectionClosedException()
                        socket = session
                        if (!sendOpen(session, eventsId, "\$events", JsonObject(emptyMap())))
                            throw LinkLost()
                    }
                    val frame = session.incoming.receive()
                    val value = frame["data"] as? JsonObject
                    if (
                        frame.string("id") != eventsId ||
                            frame.string("op") != "event" ||
                            value?.string("type") != "ready"
                    ) {
                        if (frame.string("op") == "error") throw remoteError(frame["error"])
                        throw HostProtocolException("Expected Host ready frame")
                    }
                    val clientId = value.requiredString("clientId")
                    val home =
                        (value["host"] as? JsonObject)?.requiredString("home")
                            ?: throw HostProtocolException("Missing Host home")
                    synchronized(lock) {
                        if (closed || runnerNumber != number) throw ConnectionClosedException()
                        generationNumber++
                        mutableState.value =
                            ConnectionStatus(
                                ConnectionState.Connected,
                                ConnectionGeneration(generationNumber, clientId, home),
                            )
                    }
                    attempt = 0
                }
                val session = link ?: throw LinkLost()
                for (frame in session.incoming) {
                    synchronized(lock) {
                        if (socket !== session || runnerNumber != number) throw LinkLost()
                        dispatch(frame, eventsId)
                    }
                }
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                failure = IOException("Mux ready timed out")
            } catch (error: Exception) {
                failure = error
            } finally {
                link?.close()
                synchronized(lock) {
                    if (socket === link && runnerNumber == number) {
                        socket = null
                        streams.values.forEach { it.close(LinkLost()) }
                        streams.clear()
                        mutablePending.value = emptyMap()
                        if (!closed)
                            mutableState.value = ConnectionStatus(ConnectionState.Reconnecting)
                    }
                }
            }
            synchronized(lock) {
                if (closed || runnerNumber != number) return
                if (failure is ConnectionClosedException) {
                    mutableState.value = ConnectionStatus(ConnectionState.Closed, error = failure)
                    return
                }
                if (failure is CarrierRefusedException) {
                    val unauthorized =
                        failure.code in
                            setOf(
                                "AUTH_INVALID",
                                "TOKEN_EXPIRED",
                                "AUTH_REQUIRED",
                                "ACCOUNT_AUTH_REQUIRED",
                            )
                    mutableState.value =
                        ConnectionStatus(
                            if (unauthorized) ConnectionState.Unauthorized
                            else ConnectionState.Closed,
                            error = failure,
                        )
                    return
                }
                if (failure is HostHttpException && failure.status in setOf(401, 403)) {
                    mutableState.value =
                        ConnectionStatus(ConnectionState.Unauthorized, error = failure)
                    return
                }
                mutableState.value = ConnectionStatus(ConnectionState.Reconnecting, error = failure)
            }
            val cap = listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L)[attempt.coerceAtMost(5)]
            attempt++
            delay(Random.nextLong(cap / 2, cap + 1))
        }
    }

    private fun dispatch(frame: JsonObject, eventsId: String) {
        val id = frame.requiredString("id")
        if (id == eventsId) {
            if (frame.string("op") != "event") throw LinkLost()
            val event =
                frame["data"]?.objectValue() ?: throw HostProtocolException("Missing Host event")
            val eventId = event.string("eventId")
            when (event.string("type")) {
                "waterfall" ->
                    if (eventId != null)
                        mutablePending.value = mutablePending.value + (eventId to event)
                "cancel" ->
                    if (eventId != null) mutablePending.value = mutablePending.value - eventId
            }
            if (!mutableHostEvents.tryEmit(event))
                throw HostProtocolException("Host event consumer fell behind")
            return
        }
        val channel = streams[id] ?: return
        when (frame.string("op")) {
            "event" ->
                if (!channel.trySend(frame["data"] ?: JsonNull).isSuccess) {
                    // Never silently discard durable events. Reopening gets an authoritative
                    // snapshot.
                    throw LinkLost()
                }
            "complete" -> {
                streams.remove(id)
                channel.close()
            }
            "error" -> {
                streams.remove(id)
                channel.close(remoteError(frame["error"]))
            }
            else -> throw HostProtocolException("Unknown mux frame")
        }
    }

    override fun close() =
        synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            mutableState.value =
                ConnectionStatus(ConnectionState.Closed, error = ConnectionClosedException())
            streams.values.forEach { it.close(ConnectionClosedException()) }
            streams.clear()
            mutablePending.value = emptyMap()
            socket?.close()
            socket = null
            scope.cancel()
        }

    private fun sendOpen(
        socket: MultiplexerLink?,
        id: String,
        endpoint: String,
        args: JsonObject,
    ): Boolean =
        send(
            socket,
            buildJsonObject {
                put("op", "subscribe")
                put("id", id)
                put("method", endpoint)
                put("args", args)
            },
        )

    private fun send(socket: MultiplexerLink?, frame: JsonObject): Boolean =
        socket?.send(frame) == true
}

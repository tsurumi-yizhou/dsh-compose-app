package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.terminal.TerminalEnvironment
import dev.fughetta.dshcompose.domain.terminal.TerminalFrame
import dev.fughetta.dshcompose.domain.terminal.TerminalInfo
import dev.fughetta.dshcompose.domain.terminal.TerminalInputWindow
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*

/**
 * One physical attachment. Lost input is discarded and never replayed into another shell
 * generation.
 */
class TerminalAttachment(
    scope: CoroutineScope,
    private val transport: HostTransport,
    private val sessionId: String,
    private val terminalId: String,
    private val environment: TerminalEnvironment,
) : Closeable {
    private val lock = Any()
    private val events = Channel<TerminalFrame>(8)
    val frames = events.receiveAsFlow()
    private var socket: MultiplexerLink? = null
    private val streamId = UUID.randomUUID().toString()
    private var closed = false
    private var outputSequence: Long? = null
    private var commandSequence = 0L
    private var acknowledged = 0L
    private var info: TerminalInfo? = null
    private var input = false
    private var window: TerminalInputWindow? = null

    private data class Command(val value: JsonObject, val bytes: Int)

    private val queued = ArrayDeque<Command>()
    private val pending = linkedMapOf<Long, Command>()
    private var bufferedBytes = 0L
    private var inFlightBytes = 0L
    private var requestedSize: Pair<Int, Int>? = null
    val writable: Boolean
        get() = synchronized(lock) { !closed && input && info?.running == true }

    private val worker =
        scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var link: MultiplexerLink? = null
            try {
                val physical =
                    withTimeout(transport.connectTimeoutMillis) { transport.openMuxLink() }
                link = physical
                val eventId = UUID.randomUUID().toString()
                fun open(id: String, endpoint: String, args: JsonObject) {
                    if (
                        !physical.send(
                            buildJsonObject {
                                put("op", "subscribe")
                                put("id", id)
                                put("method", endpoint)
                                put("args", args)
                            },
                        )
                    )
                        throw HostTransportException()
                }
                open(eventId, "\$events", JsonObject(emptyMap()))
                withTimeout(20000) {
                    val first = physical.incoming.receive()
                    if (first.string("op") == "error") throw remoteError(first["error"])
                    require(
                        first.string("id") == eventId &&
                            (first["data"] as? JsonObject)?.string("type") == "ready",
                    )
                }
                synchronized(lock) {
                    if (closed) throw CancellationException()
                    socket = physical
                }
                open(
                    streamId,
                    "terminal/attach",
                    buildJsonObject {
                        put("agentId", sessionId)
                        put("id", terminalId)
                    },
                )
                for (envelope in physical.incoming) {
                    if (envelope.string("id") != streamId) continue
                    when (envelope.string("op")) {
                        "error" -> throw remoteError(envelope["error"])
                        "complete" -> break
                        "event" -> {
                            val frame = terminalFrame((envelope["data"] ?: JsonNull).objectValue())
                            synchronized(lock) { accept(frame) }
                            events.send(frame)
                        }
                        else -> throw HostProtocolException("Invalid terminal envelope")
                    }
                }
                events.close()
            } catch (error: CancellationException) {
                events.cancel()
                throw error
            } catch (error: Exception) {
                events.close(error)
            } finally {
                synchronized(lock) {
                    closed = true
                    input = false
                    socket = null
                    queued.clear()
                    pending.clear()
                }
                link?.close()
            }
        }

    init {
        worker.start()
    }

    private fun accept(frame: TerminalFrame) {
        if (outputSequence == null) {
            if (frame !is TerminalFrame.Snapshot)
                throw HostProtocolException("Terminal attachment requires snapshot")
            outputSequence = frame.sequence
            window = frame.inputWindow
            info = frame.info
            input = frame.writable && frame.info.running
            flush()
            return
        }
        when (frame) {
            is TerminalFrame.Snapshot -> throw HostProtocolException("Repeated terminal snapshot")
            is TerminalFrame.Output -> {
                if (frame.sequence != outputSequence!! + 1)
                    throw HostProtocolException("Terminal output sequence gap")
                outputSequence = frame.sequence
            }
            is TerminalFrame.Status -> {
                info = frame.info
                input = frame.writable && frame.info.running
                requestedSize = null
            }
            is TerminalFrame.Ack -> {
                val command = pending.remove(frame.sequence)
                if (frame.sequence != acknowledged + 1 || command == null)
                    throw HostProtocolException("Terminal acknowledgement sequence gap")
                acknowledged = frame.sequence
                bufferedBytes -= command.bytes
                inFlightBytes -= command.bytes
                if (frame.refusal != null) {
                    input = false
                    queued.clear()
                    bufferedBytes = inFlightBytes
                }
            }
        }
        flush()
    }

    fun write(text: String): Boolean =
        synchronized(lock) {
            if (!writable || text.isEmpty()) return@synchronized false
            val limit = minOf(8192, environment.maxInputBytes)
            val chunks = mutableListOf<String>()
            val chunk = StringBuilder()
            var bytes = 0
            var totalBytes = 0L
            val points = text.codePoints().iterator()
            while (points.hasNext()) {
                val point = points.nextInt()
                val width =
                    when {
                        point < 128 -> 1
                        point < 2048 -> 2
                        point < 65536 -> 3
                        else -> 4
                    }
                totalBytes += width
                if (totalBytes + bufferedBytes > environment.maxBufferedBytes) {
                    fail("terminal/input-full")
                    return@synchronized false
                }
                if (width > limit) {
                    fail("terminal/input-full")
                    return@synchronized false
                }
                if (bytes + width > limit && bytes > 0) {
                    chunks.add(chunk.toString())
                    chunk.setLength(0)
                    bytes = 0
                }
                chunk.appendCodePoint(point)
                bytes += width
            }
            if (chunk.isNotEmpty()) chunks.add(chunk.toString())
            for (part in chunks) if (
                !enqueue(
                    buildJsonObject {
                        put("type", "write")
                        put("sequence", commandSequence + 1)
                        put("data", part)
                    },
                )
            )
                return@synchronized false
            flush()
            true
        }

    fun resize(cols: Int, rows: Int): Boolean =
        synchronized(lock) {
            if (!writable) return@synchronized false
            val size =
                cols.coerceIn(2, maxOf(2, environment.maxCols)) to
                    rows.coerceIn(1, maxOf(1, environment.maxRows))
            if (size == (requestedSize ?: info?.let { it.cols to it.rows }))
                return@synchronized false
            if (
                !enqueue(
                    buildJsonObject {
                        put("type", "resize")
                        put("sequence", commandSequence + 1)
                        put("cols", size.first)
                        put("rows", size.second)
                    },
                )
            )
                return@synchronized false
            requestedSize = size
            flush()
            true
        }

    private fun enqueue(value: JsonObject): Boolean {
        val bytes =
            value.toString().toByteArray(Charsets.UTF_8).size.toLong() +
                (window?.frameOverheadBytes ?: 0)
        if (
            bytes > Int.MAX_VALUE ||
                bytes + bufferedBytes > environment.maxBufferedBytes ||
                window?.let { bytes > it.maxBytes } == true
        ) {
            fail("terminal/input-full")
            return false
        }
        commandSequence++
        bufferedBytes += bytes
        queued.add(Command(value, bytes.toInt()))
        return true
    }

    private fun flush() {
        while (!closed && input && queued.isNotEmpty()) {
            val next = queued.first()
            if (window?.let { inFlightBytes + next.bytes > it.maxBytes } == true) return
            val sent =
                socket?.send(
                    buildJsonObject {
                        put("op", "input")
                        put("id", streamId)
                        put("data", next.value)
                    },
                ) == true
            if (!sent) {
                fail("terminal/input-full")
                return
            }
            queued.removeFirst()
            pending[next.value.requiredLong("sequence")] = next
            inFlightBytes += next.bytes
        }
    }

    private fun fail(code: String) {
        events.close(HostRemoteException(code))
        closed = true
        input = false
        socket?.close()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            input = false
            socket?.send(
                buildJsonObject {
                    put("op", "cancel")
                    put("id", streamId)
                },
            )
            socket?.close()
            queued.clear()
            pending.clear()
        }
        events.cancel()
        worker.cancel()
    }
}

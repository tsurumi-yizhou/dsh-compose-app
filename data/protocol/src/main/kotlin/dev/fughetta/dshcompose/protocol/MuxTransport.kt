package dev.fughetta.dshcompose.protocol

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.*
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonObject

/** One logical mux socket: frames in, frames out, and a release hook for the physical link. */
class MultiplexerLink(
    val incoming: ReceiveChannel<JsonObject>,
    private val sendFrame: (JsonObject) -> Boolean,
    private val release: () -> Unit,
) : Closeable {
    fun send(frame: JsonObject): Boolean = sendFrame(frame)

    override fun close() = release()
}

internal fun ktorMuxLink(socket: DefaultClientWebSocketSession): MultiplexerLink {
    val lifetime = Job(socket.coroutineContext[Job])
    val worker = CoroutineScope(socket.coroutineContext + lifetime + Dispatchers.IO)
    val frames = Channel<JsonObject>(8)
    worker.launch {
        try {
            for (raw in socket.incoming) {
                if (raw !is Frame.Text) throw HostProtocolException("Expected JSON text mux frame")
                frames.send(parseObject(raw.readText()))
            }
            frames.close()
        } catch (error: CancellationException) {
            frames.cancel()
            throw error
        } catch (error: Exception) {
            frames.close(error)
        }
    }
    return MultiplexerLink(
        incoming = frames,
        sendFrame = { frame -> socket.outgoing.trySend(Frame.Text(frame.toString())).isSuccess },
        release = {
            lifetime.cancel()
            frames.cancel()
            socket.cancel()
        },
    )
}

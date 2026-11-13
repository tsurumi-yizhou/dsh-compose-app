package dev.fughetta.dshcompose.data.nativeplugins

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

suspend fun withNativePluginInvocation(
    bridge: NativePluginApi,
    socket: NativePluginStream,
    static: List<ToolPayload.Obj>,
    request: (String) -> NativePluginInvocation,
    consume: suspend (String?, JsonObject) -> Unit,
) {
    var held = false
    try {
        val connection = if (static.isEmpty()) socket.hold().also { held = true } else null
        val result =
            if (static.isNotEmpty())
                buildJsonObject { put("effects", JsonArray(static.map { it.toJsonElement() })) }
            else {
                var response = bridge.invoke(request(checkNotNull(connection)))
                val pending = response["pending"] as? JsonObject
                if (pending != null)
                    response =
                        socket.outcome(
                            pending.npString("id")
                                ?: throw HostProtocolException("Missing deferred id"),
                            (pending["timeoutMs"] as? JsonPrimitive)?.longOrNull ?: 30_000,
                        )
                if (response.npString("t") == "void")
                    throw OperationFailure("dsh-compose/${response.npString("reason") ?: "void"}")
                response
            }
        consume(connection, result)
    } finally {
        if (held) socket.release()
    }
}

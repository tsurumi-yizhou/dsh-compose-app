package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * Asks the Host to mount [sessionId]'s Agent through a short extra follow; the chat's own follow is
 * not replaced. True means the Host opened it, not that the Agent is ready (Host events say so).
 * Any failure is false: activation must not fail the open chat.
 */
suspend fun ProtocolClient.activateForInteraction(
    sessionId: String,
    generation: ConnectionGeneration,
): Boolean {
    if (
        subagentRoute(sessionId) != null ||
            connection !is HostConnection ||
            mux.state.value.generation != generation
    )
        return false
    return runCatching {
            withTimeoutOrNull(2000) {
                var opened: ConnectionGeneration? = null
                mux.stream(
                        "session/follow",
                        buildJsonObject {
                            putJsonObject("request") {
                                put("address", sessionAddress(sessionId))
                                put("activate", true)
                                put("maxMessages", 1)
                            }
                        },
                    )
                    .first {
                        if (it is MuxEvent.Reset) opened = it.generation
                        it is MuxEvent.Item
                    }
                    .let {
                        opened == generation &&
                            (it as MuxEvent.Item).value.objectValue().string("type") == "snapshot"
                    }
            } == true
        }
        .getOrDefault(false)
}

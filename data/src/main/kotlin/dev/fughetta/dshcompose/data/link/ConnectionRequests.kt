package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.domainOperation
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.protocol.ConnectionClosedException
import dev.fughetta.dshcompose.protocol.ProtocolClient

internal fun ProtocolClient.requireGeneration(
    generation: ConnectionGeneration?,
    active: () -> Boolean,
) {
    val current = mux.state.value
    if (
        !active() ||
            current.generation != generation ||
            (generation != null && current.state != ConnectionState.Connected)
    )
        throw ConnectionClosedException()
}

internal suspend fun <T> ProtocolClient.withGeneration(
    generation: ConnectionGeneration?,
    active: () -> Boolean,
    operation: suspend () -> T,
): T {
    requireGeneration(generation, active)
    return domainOperation { operation().also { requireGeneration(generation, active) } }
}

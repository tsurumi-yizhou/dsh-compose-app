package dev.fughetta.dshcompose.domain.hosts

sealed interface LinkState {
    data object Offline : LinkState

    data class Connecting(val attempt: Long) : LinkState

    data class Online(val generation: ConnectionGeneration?) : LinkState

    data class Failed(val code: String) : LinkState
}

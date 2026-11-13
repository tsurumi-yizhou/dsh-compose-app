package dev.fughetta.dshcompose.domain.hosts

data class ConnectionGeneration(val number: Long, val clientId: String, val home: String)

enum class ConnectionState {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Unauthorized,
    Closed,
}

data class ConnectionStatus(
    val state: ConnectionState,
    val generation: ConnectionGeneration? = null,
    val error: Throwable? = null,
)

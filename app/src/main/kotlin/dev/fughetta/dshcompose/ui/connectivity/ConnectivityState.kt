package dev.fughetta.dshcompose.ui.connectivity

enum class ConnectivityKind {
    Local,
    Service,
}

data class ConnectivityState(
    val host: String = "local",
    val label: String = "",
    val kind: ConnectivityKind = ConnectivityKind.Local,
    val connected: Boolean = false,
    val error: String? = null,
)

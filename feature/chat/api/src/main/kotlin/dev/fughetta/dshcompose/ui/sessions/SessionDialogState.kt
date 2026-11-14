package dev.fughetta.dshcompose.ui.sessions

enum class SessionOperation {
    Rename,
    Fork,
    Delete,
}

data class SessionDialogState(
    val sessionId: String,
    val operation: SessionOperation,
    val title: String = "",
    val busy: Boolean = false,
    val errorCode: String? = null,
)

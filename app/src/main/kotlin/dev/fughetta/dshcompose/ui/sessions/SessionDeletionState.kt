package dev.fughetta.dshcompose.ui.sessions

import dev.fughetta.dshcompose.domain.sessions.SessionDeletionResult

enum class SessionDeletionSupport {
    Unknown,
    Supported,
    Unsupported,
}

data class SessionDeletionState(
    val support: SessionDeletionSupport = SessionDeletionSupport.Unknown,
    val sessionIds: List<String> = emptyList(),
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val deleting: Set<String> = emptySet(),
    val result: SessionDeletionResult? = null,
    val resultDialog: Boolean = false,
)

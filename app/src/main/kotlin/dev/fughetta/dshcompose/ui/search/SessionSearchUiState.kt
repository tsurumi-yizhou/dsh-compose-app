package dev.fughetta.dshcompose.ui.search

import dev.fughetta.dshcompose.domain.sessions.search.*

enum class ContentSearchStatus {
    Idle,
    Pending,
    Ready,
    Unavailable,
    Failed,
}

data class SessionSearchUiState(
    val revision: Long = 0,
    val epoch: Long = 0,
    val input: String = "",
    val query: String = "",
    val results: SessionSearchResults = SessionSearchResults(),
    val status: ContentSearchStatus = ContentSearchStatus.Idle,
    val hasMore: Boolean = false,
    val deriving: Boolean = false,
)

package dev.fughetta.dshcompose.domain.drafts

import kotlinx.coroutines.flow.StateFlow

data class DraftsState(
    val values: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
    val storageErrorRevision: Long = 0,
)

interface DraftsRepository {
    val state: StateFlow<DraftsState>

    suspend fun save(sessionId: String, text: String)

    suspend fun forget(sessionId: String)
}

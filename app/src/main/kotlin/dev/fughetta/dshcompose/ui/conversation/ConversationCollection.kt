package dev.fughetta.dshcompose.ui.conversation

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.*

@Composable
internal fun collectConversation(
    source: StateFlow<ConversationsSnapshot>,
    id: String,
): ChatUiState? {
    return key(source, id) {
        val selected = remember { source.map { it.chats[id] }.distinctUntilChanged() }
        selected.collectAsStateWithLifecycle(initialValue = source.value.chats[id]).value
    }
}

@Composable
internal fun collectConversationError(
    source: StateFlow<ConversationsSnapshot>,
    id: String?,
): String? {
    return key(source, id) {
        val selected = remember { source.map { it.chats[id]?.error }.distinctUntilChanged() }
        selected.collectAsStateWithLifecycle(initialValue = source.value.chats[id]?.error).value
    }
}

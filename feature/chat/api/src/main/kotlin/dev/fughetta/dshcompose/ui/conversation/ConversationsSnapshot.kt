package dev.fughetta.dshcompose.ui.conversation

import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.*

data class ConversationsSnapshot(
    val revision: Long = 0,
    val chats: PersistentMap<String, ChatUiState> = persistentMapOf(),
)

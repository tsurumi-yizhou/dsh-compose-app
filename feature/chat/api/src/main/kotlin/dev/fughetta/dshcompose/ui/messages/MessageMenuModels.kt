package dev.fughetta.dshcompose.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import dev.fughetta.dshcompose.domain.chat.ChatItem

enum class MessageOperation {
    Copy,
    Share,
    Quote,
    Branch,
    Edit,
    Regenerate,
}

data class MessageMenuState(
    val sessionId: String,
    val row: ChatItem,
    val branchCut: Long? = null,
    val quoteable: Boolean = true,
    val editable: Boolean = false,
    val regeneratePrompt: ChatItem? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

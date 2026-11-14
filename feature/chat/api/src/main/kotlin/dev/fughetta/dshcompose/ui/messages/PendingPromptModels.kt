package dev.fughetta.dshcompose.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.collections.immutable.*

enum class PendingPromptUiPhase {
    Sending,
    Uncertain,
    Accepted,
    Rejected,
}

data class PendingPromptUi(
    val requestId: String,
    val phase: PendingPromptUiPhase,
    val text: String,
    val quote: String?,
    val attachmentNames: PersistentList<String> = persistentListOf(),
    val errorCode: String? = null,
)

data class PendingInboxUi(val item: InboxItem, val canRestore: Boolean)

data class PendingPromptsUiState(
    val owner: PendingPromptsOwner? = null,
    val pending: PersistentList<PendingPromptUi> = persistentListOf(),
    val inbox: PersistentList<PendingInboxUi> = persistentListOf(),
    val busyItem: String? = null,
    val error: String? = null,
)

data class PendingPromptsAction(val owner: PendingPromptsOwner, val command: PendingPromptsCommand)

sealed interface PendingPromptsCommand {
    data class Retry(val requestId: String) : PendingPromptsCommand

    data class RestoreRejected(val requestId: String) : PendingPromptsCommand

    data class Remove(val item: InboxItem) : PendingPromptsCommand

    data class Restore(val item: InboxItem) : PendingPromptsCommand

    data class Steer(val item: InboxItem) : PendingPromptsCommand

    data class Edit(val item: InboxItem, val text: String) : PendingPromptsCommand
}

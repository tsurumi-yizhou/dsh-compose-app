package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.collections.immutable.*

fun MessagesRepository.project(
    owner: PendingPromptsOwner,
    inbox: List<InboxItem>,
    busyItem: String? = null,
    error: String? = null,
): PendingPromptsUiState {
    val local = state.value.values.filter { it.owner == owner }
    val visible = inbox.filterNot { it.hidden }
    val inboxIds = visible.filter { it.sourceKind == "user" }.mapNotNull { it.requestId }.toSet()
    val retainedItems = local.mapNotNull { it.itemId }.toSet()
    return PendingPromptsUiState(
        owner,
        local
            .filterNot { value ->
                value.state == PendingPromptState.Queued && value.requestId in inboxIds
            }
            .map { value ->
                PendingPromptUi(
                    value.requestId,
                    when (value.state) {
                        PendingPromptState.Sending -> PendingPromptUiPhase.Sending
                        PendingPromptState.Uncertain -> PendingPromptUiPhase.Uncertain
                        PendingPromptState.Queued -> PendingPromptUiPhase.Accepted
                        PendingPromptState.Rejected,
                        PendingPromptState.Recovered -> PendingPromptUiPhase.Rejected
                    },
                    value.composerText,
                    value.quote,
                    value.files.map { it.name }.toPersistentList(),
                    value.errorCode,
                )
            }
            .toPersistentList(),
        visible
            .map { item -> PendingInboxUi(item, item.id in retainedItems || item.textOnly) }
            .toPersistentList(),
        busyItem,
        error,
    )
}

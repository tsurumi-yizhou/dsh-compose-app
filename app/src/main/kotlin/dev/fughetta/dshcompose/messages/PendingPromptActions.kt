package dev.fughetta.dshcompose.messages

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.messages.splitQuote
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.refreshPendingPrompts(id: String, error: String? = null): Unit {
    run {
        val home =
            selectedConnection.state.value.generation?.home ?: lastHome(selectedHost) ?: return
        val owner = PendingPromptsOwner(selectedHost, home, id)
        val inbox = conversations.state.value.chats[id]?.inbox.orEmpty()
        val view = pendingPrompts.project(owner, inbox, pendingOperations[owner], error)
        updateChat(id) { it.copy(pendingPrompts = view) }
    }
}

internal fun AppViewModel.handlePendingPrompt(action: PendingPromptsAction): Unit {
    run {
        val owner = sessionRepository.connection.value ?: return
        val generation = selectedConnection.state.value.generation ?: return
        val target = action.owner
        fun valid() =
            sessionRepository.connection.value === owner &&
                selectedHost == target.host &&
                generation.home == target.home &&
                selectedConnection.state.value.generation == generation &&
                watchedId == target.sessionId
        if (!valid() || target in pendingOperations) return
        val chat = conversations.state.value.chats[target.sessionId] ?: return
        if (chat.syncing || chat.sending || chat.readOnly) return
        val command = action.command
        val key =
            when (command) {
                is PendingPromptsCommand.Retry -> command.requestId
                is PendingPromptsCommand.RestoreRejected -> command.requestId
                is PendingPromptsCommand.Remove -> command.item.id
                is PendingPromptsCommand.Restore -> command.item.id
                is PendingPromptsCommand.Steer -> command.item.id
                is PendingPromptsCommand.Edit -> command.item.id
            }
        pendingOperations[target] = key
        refreshPendingPrompts(target.sessionId)
        viewModelScope.launch {
            var failure: String? = null
            try {
                when (command) {
                    is PendingPromptsCommand.Retry ->
                        pendingPrompts.retryDelivery(target, command.requestId)
                    is PendingPromptsCommand.RestoreRejected -> {
                        val pending =
                            pendingPrompts.find(target, command.requestId)
                                ?: throw OperationFailure("message/pending-not-found")
                        ensureComposerCanRecover(
                            target.sessionId,
                            pending.composerText,
                            pending.quote,
                            pending.files,
                        )
                        pendingPrompts
                            .recoverDraft(target, command.requestId)
                            ?.let(::restorePendingDraft)
                    }
                    else -> {
                        val requested =
                            when (command) {
                                is PendingPromptsCommand.Remove -> command.item
                                is PendingPromptsCommand.Restore -> command.item
                                is PendingPromptsCommand.Steer -> command.item
                                is PendingPromptsCommand.Edit -> command.item
                            }
                        val item =
                            conversations.state.value.chats[target.sessionId]
                                ?.inbox
                                ?.firstOrNull { it.id == requested.id && it == requested }
                                ?.takeUnless { it.automatic }
                                ?: throw OperationFailure("message/queue-changed")
                        val local = pendingPrompts.byItem(target, item.id)
                        if (command is PendingPromptsCommand.Restore) {
                            if (
                                pendingPrompts.state.value.values.count {
                                    it.state == PendingPromptState.Recovered
                                } >= 64
                            )
                                throw OperationFailure("message/pending-storage-full")
                            if (local == null && !item.textOnly)
                                throw OperationFailure("message/attachment-unavailable")
                            val quote = splitQuote(item.text)
                            ensureComposerCanRecover(
                                target.sessionId,
                                quote.body,
                                quote.quote,
                                local?.files.orEmpty(),
                            )
                        }
                        val change =
                            when (command) {
                                is PendingPromptsCommand.Edit -> {
                                    if (!item.textOnly || command.text.isBlank())
                                        throw OperationFailure("message/queue-edit-unavailable")
                                    QueueChange.Edit(command.text)
                                }
                                is PendingPromptsCommand.Steer -> QueueChange.Steer
                                else -> QueueChange.Remove
                            }
                        owner.updateQueuedPrompt(target.sessionId, item.id, change)
                        if (change == QueueChange.Remove) {
                            val content =
                                if (command is PendingPromptsCommand.Restore)
                                    splitQuote(item.text).let {
                                        RecoveredPromptContent(it.body, it.quote)
                                    }
                                else null
                            val restored =
                                pendingPrompts.withdrawn(
                                    target,
                                    item.id,
                                    restore = content != null,
                                    content = content,
                                )
                            if (content != null) {
                                val recovered =
                                    restored
                                        ?: pendingPrompts.retainWithdrawnText(
                                            target,
                                            item.text,
                                            content,
                                        )
                                if (valid()) {
                                    try {
                                        ensureComposerCanRecover(
                                            target.sessionId,
                                            recovered.composerText,
                                            recovered.quote,
                                            recovered.files,
                                        )
                                        pendingPrompts
                                            .recoverDraft(target, recovered.requestId)
                                            ?.let(::restorePendingDraft)
                                    } catch (_: OperationFailure) {
                                        failure = "message/composer-not-empty"
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = operationErrorCode(error) ?: "message/queue-action-failed"
            } finally {
                if (pendingOperations[target] == key) pendingOperations.remove(target)
                if (valid()) refreshPendingPrompts(target.sessionId, failure)
            }
        }
    }
}

internal fun AppViewModel.ensureComposerCanRecover(
    id: String,
    text: String,
    quote: String?,
    files: List<ComposerFile>,
): Unit {
    run {
        val chat = composer.input(id)
        if (chat.draft.isNotBlank() && (chat.draft != text || chat.quote != quote))
            throw OperationFailure("message/composer-not-empty")
        try {
            composerAttachments.ensureCapacity(id, files)
        } catch (_: dev.fughetta.dshcompose.domain.attachments.AttachmentImportException) {
            throw OperationFailure("message/pending-storage-full")
        }
    }
}

internal fun AppViewModel.restorePendingDraft(pending: PendingPrompt): Unit {
    run {
        composerAttachments.add(pending.sessionId, pending.files)
        updateComposer(pending.sessionId) {
            it.copy(
                draft = pending.composerText,
                quote = pending.quote,
                draftCursor = pending.composerText.length,
            )
        }
    }
}

internal fun AppViewModel.restorePersistedPrompts(sessionId: String) {
    val host = selectedHost
    val home = selectedConnection.state.value.generation?.home ?: lastHome(host)
    viewModelScope.launch {
        val restored = pendingPrompts.restore(host, home)
        if (selectedHost == host && restored.any { it.sessionId == sessionId })
            refreshPendingPrompts(sessionId)
    }
}

internal fun AppViewModel.clearDeliveredDraft(delivered: DeliveredPrompt) {
    val pending = delivered.prompt
    if (
        selectedHost != pending.host ||
            watchedId != pending.sessionId ||
            selectedConnection.state.value.generation != delivered.generation
    )
        return
    updateComposer(pending.sessionId) { current ->
        if (current.draft == pending.composerText && current.quote == pending.quote)
            current.copy(
                draft = "",
                quote = null,
                draftSpans = persistentListOf(),
                draftSelectionEnd = null,
                draftCursor = null,
            )
        else current
    }
}

package dev.fughetta.dshcompose.sessions

import android.app.Application
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.ClientApplication
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.attachments.importShares
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.messages.withQuote
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.messages.refreshPendingPrompts
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.conversation.CommandReply
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal suspend fun AppViewModel.applyModel(
    id: String,
    owner: ConnectedHost,
    result: ModelSelectionResult,
): Unit {
    run {
        if (client !== owner) return
        val transcript = activeConversation?.takeIf { watchedId == id }
        transcript?.confirmModel(result.selected)
        if (client === owner)
            updateChat(id) {
                it.copy(
                    model = if (transcript == null) result.selected else it.model,
                    defaultModelSaveFailed =
                        when (result.defaultSave) {
                            ModelDefaultSaveStatus.Failed -> true
                            ModelDefaultSaveStatus.Saved,
                            ModelDefaultSaveStatus.Skipped -> false
                            ModelDefaultSaveStatus.Unknown -> it.defaultModelSaveFailed
                        },
                )
            }
    }
}

internal suspend fun AppViewModel.sendDraft(owner: ConnectedHost): Unit {
    run {
        if (draftCreating) return
        val chat =
            conversations.state.value.chats[DRAFT_SESSION_ID]?.withComposer(
                composer.input(DRAFT_SESSION_ID),
            ) ?: return
        if (chat.draft.isBlank() && composerAttachments.selected(DRAFT_SESSION_ID).isEmpty()) return
        val workspace = state.value.workspaces
        if (!workspace.preferencesLoaded || workspace.loading) {
            updateChat(DRAFT_SESSION_ID) { it.copy(error = "workspace/not-ready") }
            return
        }
        val operations = owner.repositories?.sessions ?: return
        val models = (app as ClientApplication).sessionModelsRepository.value
        val number = connectionNumber
        val selectionRevision = state.value.openSessionRevision
        draftCreating = true
        updateChat(DRAFT_SESSION_ID) { it.copy(sending = true, error = null) }
        var createdId: String? = null
        try {
            val id =
                operations.create(
                    cwd = hostWorkingDirectory.takeIf { workspace.selectedId == null },
                    workspaceId = workspace.selectedId,
                    agentPreset = chat.agentPreset,
                )
            if (client !== owner || connectionNumber != number) return
            createdId = id
            val draft = imports.withLock {
                if (client !== owner || connectionNumber != number) return
                val current =
                    conversations.state.value.chats[DRAFT_SESSION_ID]?.withComposer(
                        composer.input(DRAFT_SESSION_ID),
                    ) ?: chat
                composerAttachments.move(DRAFT_SESSION_ID, id)
                composer.promote(DRAFT_SESSION_ID, id)
                conversations.promoteDraft(id)
                if (pickerSession?.first == DRAFT_SESSION_ID) pickerSession = id to number
                current
            }
            if (
                state.value.selectedSessionId == null &&
                    state.value.openSessionRevision == selectionRevision
            ) {
                mutableState.update {
                    it.copy(
                        openSessionId = id,
                        openSessionRevision = it.openSessionRevision + 1,
                    )
                }
                openSession(id)
            }
            // A model picked in the draft applies before the first message; if the Host refuses
            // it, nothing is sent and the draft text waits in the new conversation.
            draft.model?.let { selection ->
                val result =
                    (models
                            ?: throw dev.fughetta.dshcompose.domain.providers
                                .ModelOperationException("connection-lost"))
                        .select(id, selection)
                if (client !== owner || connectionNumber != number) return
                applyModel(id, owner, result)
            }
            sendMessage(owner, id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (client === owner && connectionNumber == number)
                updateChat(createdId ?: DRAFT_SESSION_ID) {
                    it.copy(
                        error =
                            (error as? SessionOperationException)?.code
                                ?: operationErrorCode(error)
                                ?: "failed",
                    )
                }
        } finally {
            if (connectionNumber == number) {
                draftCreating = false
                if (DRAFT_SESSION_ID in conversations.state.value.chats)
                    updateChat(DRAFT_SESSION_ID) { it.copy(sending = false) }
            }
        }
    }
}

internal fun AppViewModel.queueOfflineSend(id: String): Boolean = run {
    val chat = conversations.state.value.chats[id] ?: return false
    val draft = composer.input(id).draft
    val quote = composer.input(id).quote
    val host = selectedHost
    val home = lastHome(host)
    val rejection =
        OfflineSendPolicy.rejection(
            draftSession = id == DRAFT_SESSION_ID,
            sending = chat.sending,
            syncing = chat.syncing,
            running = chat.running,
            draft = draft,
            quote = quote,
            attachmentCount = composerAttachments.selected(id).size,
            mediaGeneration = MediaGenerationHeader.read(draft) != null,
            home = home,
        )
    if (rejection != null || home == null) return false
    val text = if (quote.isNullOrBlank()) draft else withQuote(quote, draft)
    val pending =
        pendingPrompts.begin(
            host = host,
            home = home,
            sessionId = id,
            text = text,
            composerText = draft,
            quote = quote,
            files = emptyList(),
            mode = PromptMode.Queue,
            timeZone = java.util.TimeZone.getDefault().id,
        )
    pendingPrompts.failed(pending.token, java.io.IOException("message/offline-queued"))
    updateComposer(id) { current ->
        if (current.draft != draft) current
        else
            current.copy(
                draft = "",
                quote = null,
                draftSpans = persistentListOf(),
                draftSelectionEnd = null,
                draftCursor = null,
            )
    }
    refreshPendingPrompts(id)
    pendingPrompts.persist(host, home)
    true
}

internal suspend fun AppViewModel.sendMessage(
    owner: ConnectedHost,
    id: String,
    overrideText: String? = null,
    isCurrent: () -> Boolean = { true },
): Boolean {
    return run {
        val generation = owner.state.value.generation ?: return false
        fun valid() = client === owner && owner.state.value.generation == generation && isCurrent()
        if (!valid()) return false
        val override = overrideText != null
        val draft = overrideText ?: composer.input(id).draft
        val quote = if (override) null else composer.input(id).quote
        val files = if (override) emptyList() else composerAttachments.selected(id)
        if (conversations.state.value.chats[id]?.readOnly == true) return false
        if (
            draft.isBlank() && quote.isNullOrBlank() && files.isEmpty() ||
                conversations.state.value.chats[id]?.sending == true
        )
            return false
        var accepted = false
        val sendOwner = Any()
        sendOwners[id] = sendOwner
        updateChat(id) { it.copy(sending = true, error = null) }
        try {
            if (
                !override &&
                    files.isEmpty() &&
                    quote.isNullOrBlank() &&
                    state.value.mediaGeneration.modes[id] == null &&
                    MediaGenerationHeader.read(draft) == null
            ) {
                val input = parseSlashInput(draft)
                if (input != null && input.token.isEmpty()) return false
                val resolution = commands.resolve(id, draft)
                if (!valid() || id in retiredSessions) return false
                if (resolution != null) {
                    if (
                        resolution.picked &&
                            (resolution.command.takesInput ||
                                resolution.command.kind == SlashCommandKind.Skill)
                    ) {
                        applyCommandPick(id, resolution.command, draft.length)
                        return false
                    }
                    if (resolution.command.kind == SlashCommandKind.App) {
                        val action =
                            appCommandAction(resolution.command.name, id)
                                ?: throw OperationFailure("command/unavailable")
                        clearCommandDraft(id, draft)
                        onAction(action)
                    } else {
                        val result = commands.execute(resolution)
                        if (valid() && id !in retiredSessions) {
                            updateChat(id) {
                                it.copy(lastCommand = CommandReply(resolution.line, result))
                            }
                            if (watchedId == id)
                                activeConversation?.let { current ->
                                    current.confirmCommand(result, resolution.line)
                                }
                            if (valid()) clearCommandDraft(id, draft)
                        }
                    }
                    return valid()
                }
            }
            val uploadRepository =
                connectionSession?.takeIf { it === owner }?.messageAttachments ?: return false
            val prepared =
                uploadRepository.prepare(id, files) { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        val total = progress.totalBytes
                        if (valid())
                            updateChat(id) { chat ->
                                chat.copy(
                                    attachments =
                                        chat.attachments.map { item ->
                                            if (item.id == progress.attachmentId)
                                                item.copy(
                                                    progress =
                                                        if (total != null && total > 0)
                                                            progress.bytes.toFloat() / total
                                                        else 0f,
                                                )
                                            else item
                                        },
                                )
                            }
                    }
                }
            if (!valid() || id in retiredSessions) return false
            val workspacePaths = prepared.workspacePaths
            val originalHeader = MediaGenerationHeader.read(draft)
            val originalParameters = originalHeader?.parameters
            val composed =
                if (override) draft
                else if (!quote.isNullOrBlank() && originalParameters != null)
                    MediaGenerationHeader.format(
                        originalParameters,
                        withQuote(quote, originalHeader.prompt.orEmpty()),
                    )
                else mediaGeneration.preparePrompt(id, withQuote(quote, draft))
            val text =
                if (workspacePaths.isEmpty()) composed
                else {
                    val hint =
                        getApplication<Application>()
                            .getString(R.string.workspace_uploaded_files_hint)
                    listOf(
                            composed.takeIf { it.isNotEmpty() },
                            hint +
                                "\n" +
                                workspacePaths.joinToString("\n") { "- ${JsonPrimitive(it)}" },
                        )
                        .filterNotNull()
                        .joinToString("\n\n")
                }
            val pending =
                pendingPrompts.begin(
                    selectedHost,
                    generation.home,
                    id,
                    text,
                    draft,
                    quote,
                    files,
                    timeZone = java.util.TimeZone.getDefault().id,
                    fileReceiptIds = prepared.fileReceiptIds,
                    composerOwned = !override,
                )
            // The in-memory ledger owns these spool files until the engine proves consumption.
            if (!override) {
                composerAttachments.detach(id, files.mapTo(mutableSetOf()) { it.id })
            }
            accepted = submitPendingMessage(pending, prepared.submission)
            if (accepted && valid() && !override) {
                composerAttachments.detach(id, files.mapTo(mutableSetOf()) { it.id })
                uploadRepository.forget(files.mapTo(mutableSetOf()) { it.id })
                composer.sent(id, draft, quote)
                updateChat(id) { it }
                references.externalDraft(id, composer.input(id).draft)
            }
        } finally {
            if (sendOwners[id] === sendOwner) {
                sendOwners.remove(id)
                if (client === owner) {
                    updateChat(id) {
                        it.copy(
                            sending = false,
                            attachments = it.attachments.map { item -> item.copy(progress = null) },
                        )
                    }
                    importShares()
                }
            }
        }
        return accepted && valid()
    }
}

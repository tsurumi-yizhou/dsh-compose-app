package dev.fughetta.dshcompose.sessions

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.ClientApplication
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.engine.runtime.AutoPresetRepair
import dev.fughetta.dshcompose.restartLocalRuntime
import dev.fughetta.dshcompose.revealOwnedSession
import dev.fughetta.dshcompose.sync.SessionSyncWork
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.drawerAction(action: DrawerAction): Unit {
    run {
        when (action) {
            is DrawerAction.MarkSeen -> markSessionSeen(action.id)
            is DrawerAction.RequestDeletion -> sessionsModel.requestDeletion(action.ids)
            DrawerAction.ConfirmDeletion -> confirmDrawerDeletion()
            DrawerAction.DismissDeletion -> sessionsModel.dismissDeletion()
            else -> drawerUi.handle(action)
        }
    }
}

internal fun AppViewModel.selectDraft(): Unit {
    run {
        settings.rememberSession(selectedHost, null)
        watchedId?.let { previous ->
            markSessionSeen(previous)
            replyLinks.dismissSession(previous)
            conversations.releaseTranscript(
                previous,
                hosts.nextTranscriptRevision(selectedConnection),
            )
        }

        references.dismiss()
        conversationModel.resetMessage()
        watchedId = null
        conversationModel.clear()
        terminal.connect(connectionSession?.repositories?.terminals, selectedHost, null)
        usage.monitor.connect(connectionSession?.repositories?.usage, selectedHost, null)
        refreshUsageAccount()
        commands.connect(sessionRepository.connection.value)
        nativePlugins.session(null)
        mediaGeneration.session(null)
        hosts.selectSession(selectedConnection, null)
        updateChat(DRAFT_SESSION_ID) { it.copy(syncing = false) }
        mutableState.update {
            it.copy(
                selectedSessionId = null,
                openSessionId = null,
                openSessionRevision = it.openSessionRevision + 1,
            )
        }
        refreshSessionAttention()
    }
}

internal fun AppViewModel.selectAgentPreset(id: String, preset: String): Unit {
    run {
        if (id == DRAFT_SESSION_ID) {
            updateChat(id) { it.copy(agentPreset = preset) }
            return
        }
        val owner = client ?: return
        val operations = (app as ClientApplication).agentPresetsRepository.value ?: return
        val number = connectionNumber
        viewModelScope.launch {
            try {
                val selected = operations.select(id, preset)
                if (client !== owner || connectionNumber != number) return@launch
                val transcript = activeConversation?.takeIf { watchedId == id }
                transcript?.confirmAgentPreset(selected)
                updateChat(id) {
                    it.copy(
                        agentPreset = if (transcript == null) selected else it.agentPreset,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (client === owner && connectionNumber == number)
                    updateChat(id) { it.copy(error = error.message) }
            }
        }
    }
}

internal suspend fun AppViewModel.cleanupDeletedSession(id: String): Unit {
    run {
        settings.forgetSession(selectedHost, id)
        val wasSelected = state.value.selectedSessionId == id
        val number = connectionNumber
        retiredSessions.add(id)
        composer.forget(id)
        workspaceFiles.dismissSession(id)
        replyLinks.dismissSession(id)
        references.dismissSession(id)
        if (pickerSession?.first == id) pickerSession = null
        if (watchedId == id) {

            watchedId = null
            conversationModel.clear()
        }
        val files = composerAttachments.take(id)
        connectionSession?.messageAttachments?.forgetSession(id)
        client?.state?.value?.generation?.let {
            pendingPrompts.sessionDeleted(PendingPromptsOwner(selectedHost, it.home, id))
        }
        conversations.remove(id)
        mutableState.update {
            it.copy(
                selectedSessionId = it.selectedSessionId?.takeUnless { selected -> selected == id },
                openSessionId = it.openSessionId?.takeUnless { open -> open == id },
                deletedSessionId = id,
            )
        }
        if (wasSelected) selectDraft()
        refreshSessionAttention()
        try {
            composerFiles.release(files)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (connectionNumber == number)
                mutableState.update { it.copy(error = "session/attachment-cleanup-failed") }
        }
    }
}

internal fun AppViewModel.foreground(value: Boolean): Unit {
    run {
        foreground = value
        nativePlugins.foreground(value)
        deepSeekAccount.foreground(value)
        backgroundNet.visible(value)
        notificationSettings.visibility(value, selectedHost, visibleSessionId)
        refreshSessionAttention()
        if (!value && client != null) {
            // One constrained background refresh uses the application-owned endpoint registry.
            SessionSyncWork.requestNow(app, selectedHost)
        }
        if (value) {
            uiConfig.kick()
            // Pull trigger: a live host gets a debounced directory refresh on return to the
            // foreground; the repository writes the answer through to the local mirror.
            if (client != null) sessionRepository.scheduleRefresh()
        }
    }
}

internal fun AppViewModel.openPublicLink(address: String): Unit {
    run {
        try {
            getApplication<Application>()
                .startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(address))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
        } catch (_: Exception) {
            islandExternalFailed()
        }
    }
}

internal fun AppViewModel.prepareConversationDraft(text: String): Unit {
    run {
        val owner = client
        if (owner == null) {
            pendingComposeText = text
            openFeature("engine")
            return
        }
        val operations = owner.repositories?.sessions ?: return
        pendingComposeText = null
        viewModelScope.launch {
            try {
                val workspace = state.value.workspaces.selectedId
                val id =
                    operations.create(
                        cwd = hostWorkingDirectory.takeIf { workspace == null },
                        workspaceId = workspace,
                    )
                if (client !== owner) return@launch
                updateComposer(id) { it.copy(draft = text, draftCursor = text.length) }
                revealOwnedSession(owner, id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (client === owner) mutableState.update { it.copy(error = error.message) }
            }
        }
    }
}

internal fun AppViewModel.repairAutoDefaultPreset(): Unit {
    run {
        viewModelScope.launch(Dispatchers.IO) {
            val rootfs =
                File(getApplication<Application>().filesDir, "runtime/rootfs").takeIf {
                    it.isDirectory
                }
            if (rootfs == null || !AutoPresetRepair.clear(rootfs)) return@launch
            runCatching { restartLocalRuntime() }
                .onFailure {
                    android.util.Log.w("Permissions", "restart after clearing Auto failed: $it")
                }
        }
    }
}

internal fun AppViewModel.updateSessionInventory(
    event: dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
): Unit {
    run {
        if (event.text("event") == "api-session/removed") {
            val id = event.arrayItems("args")?.firstOrNull()?.primitiveContent ?: return
            mutateDrawer { it.forgetSessions(setOf(id)) }
        }
    }
}

internal fun AppViewModel.scheduleInventoryRefresh(owner: ConnectedHost): Unit {
    run { if (client === owner) sessionRepository.scheduleRefresh() }
}

internal suspend fun AppViewModel.refreshSessions(owner: ConnectedHost): Unit {
    run {
        if (client !== owner) return
        sessionRepository.refresh()
        if (client !== owner) return
        mutableState.update { it.copy(error = null) }
        refreshSessionAttention()
    }
}

/**
 * No link: keep a plain-text prompt (plus its quote) as a durable pending write. The reconnect path
 * resends it with the same request id, so the engine can de-duplicate. Attachments, slash commands
 * and media headers still need the engine and are rejected here.
 */
internal fun AppViewModel.questionsFor(id: String): List<QuestionUiState> = run {
    val requests = client?.requests?.value ?: return emptyList()
    val generation = requests.generation ?: return emptyList()
    requests.questions.values
        .filter { it.sessionId == id }
        .map { request ->
            QuestionUiState(
                request,
                connectionNumber,
                generation.number,
                questionDrafts[request.eventId].orEmpty(),
                request.eventId in requestResponses,
                questionRemaining[request.eventId],
            )
        }
}

internal fun AppViewModel.approvalsFor(id: String): List<ApprovalUiState> = run {
    val requests = client?.requests?.value ?: return emptyList()
    val generation = requests.generation ?: return emptyList()
    requests.approvals.values
        .filter { it.sessionId == id }
        .map { request ->
            ApprovalUiState(
                request.eventId,
                request.toolName,
                request.reason,
                request.displayReason,
                connectionNumber,
                generation.number,
                request.eventId in requestResponses,
            )
        }
}

internal fun AppViewModel.clearCommandDraft(id: String, expected: String): Unit {
    run {
        updateComposer(id) {
            if (it.draft == expected)
                it.copy(
                    draft = "",
                    draftCursor = null,
                    draftSelectionEnd = null,
                    draftSpans = persistentListOf(),
                )
            else it
        }
        if (watchedId == id) commands.composer(id, composer.input(id).draft, false)
    }
}

internal fun AppViewModel.pickCommand(id: String, name: String): Unit {
    run {
        val owner = client ?: return
        if (conversations.state.value.chats[id]?.sending == true) return
        viewModelScope.launch {
            try {
                val command = commands.pick(id, name) ?: return@launch
                if (client === owner && watchedId == id) applyCommandPick(id, command, null)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (client === owner)
                    updateChat(id) {
                        it.copy(
                            error =
                                getApplication<Application>()
                                    .getString(UiR.string.commands_unavailable),
                        )
                    }
            }
        }
    }
}

internal fun AppViewModel.applyCommandPick(id: String, command: SlashCommand, caret: Int?): Unit {
    run {
        val chat = composer.input(id)
        val text = chat.draft
        val input = parseSlashInput(text) ?: return
        val leading = text.length - text.trimStart().length
        val end =
            if (commands.known(input.token)) leading + 1 + input.token.length
            else
                (caret ?: chat.draftCursor ?: chat.draftSelectionEnd ?: text.length).coerceIn(
                    leading + 1,
                    text.length,
                )
        val suffix = text.substring(end)
        val prefix = text.substring(0, leading) + "/${command.name}"
        val needsInput =
            command.kind == SlashCommandKind.Skill || command.takesInput || suffix.isNotEmpty()
        val replacement =
            prefix +
                if (needsInput && (suffix.isEmpty() || !suffix.first().isWhitespace())) " " else ""
        val next = replacement + suffix
        val delta = replacement.length - end
        updateComposer(id) {
            it.copy(
                draft = next,
                draftCursor = (prefix.length + if (needsInput) 1 else 0).coerceAtMost(next.length),
                draftSpans =
                    it.draftSpans
                        .filter { span -> span.start >= end }
                        .map { span ->
                            span.copy(start = span.start + delta, end = span.end + delta)
                        }
                        .toPersistentList(),
            )
        }
        commands.composer(id, next, false)
        if (!needsInput) onAction(AppAction.Send(id))
    }
}

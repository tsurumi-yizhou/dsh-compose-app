package dev.fughetta.dshcompose.nativeplugins

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.CommandReply
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal suspend fun AppViewModel.handleNativePluginEffect(
    dispatch: dev.fughetta.dshcompose.ui.nativeplugins.NativePluginDispatch,
): Unit {
    run {
        if (dispatch.hostKey != selectedHost || dispatch.scope.sessionId != watchedId) return
        if (
            nativePlugins.generation != dispatch.binding ||
                client?.state?.value?.generation != dispatch.muxGeneration
        )
            return
        val id = dispatch.scope.sessionId
        when (val effect = dispatch.effect) {
            is dev.fughetta.dshcompose.ui.nativeplugins.NativePluginEffect.Insert ->
                if (id != null)
                    updateComposer(id) { chat ->
                        if (conversations.state.value.chats[id]?.sending == true) chat
                        else {
                            val at =
                                (chat.draftCursor ?: chat.draftSelectionEnd ?: chat.draft.length)
                                    .coerceIn(0, chat.draft.length)
                            val text =
                                chat.draft.substring(0, at) + effect.text + chat.draft.substring(at)
                            val cursor = at + effect.text.length
                            val edited =
                                editReferenceDraft(
                                    ReferenceInput(chat.draft, at, at),
                                    ReferenceInput(text, cursor, cursor),
                                    chat.draftSpans,
                                    false,
                                )
                            chat.copy(
                                draft = edited.input.text,
                                draftCursor = edited.input.end,
                                draftSelectionEnd = edited.input.end,
                                draftSpans = edited.spans,
                            )
                        }
                    }
            is dev.fughetta.dshcompose.ui.nativeplugins.NativePluginEffect.Command ->
                if (id != null) runNativePluginCommand(id, effect.line)
            is dev.fughetta.dshcompose.ui.nativeplugins.NativePluginEffect.Open ->
                openNativePluginPage(effect.page, id)
            is dev.fughetta.dshcompose.ui.nativeplugins.NativePluginEffect.Link ->
                try {
                    getApplication<Application>()
                        .startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                } catch (_: Exception) {
                    islandExternalFailed()
                }
            is dev.fughetta.dshcompose.ui.nativeplugins.NativePluginEffect.Toast ->
                android.widget.Toast.makeText(
                        getApplication(),
                        effect.text,
                        android.widget.Toast.LENGTH_SHORT,
                    )
                    .show()
        }
    }
}

internal fun AppViewModel.runNativePluginCommand(id: String, line: String): Unit {
    run {
        val owner = client ?: return
        viewModelScope.launch {
            try {
                val resolved =
                    commands.resolve(id, line)
                        ?: throw OperationFailure("native-plugin/unknown-command")
                if (client !== owner || watchedId != id) return@launch
                if (resolved.command.kind == SlashCommandKind.App) {
                    val action =
                        appCommandAction(resolved.command.name, id)
                            ?: throw OperationFailure("native-plugin/unknown-command")
                    onAction(action)
                } else {
                    val result = commands.execute(resolved)
                    if (client !== owner || id in retiredSessions) return@launch
                    updateChat(id) { it.copy(lastCommand = CommandReply(resolved.line, result)) }
                    if (watchedId == id)
                        activeConversation?.let { transcript ->
                            transcript.confirmCommand(result, resolved.line)
                        }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (client === owner) updateChat(id) { it.copy(error = error.message) }
            }
        }
    }
}

internal fun AppViewModel.openNativePluginPage(page: String, id: String?): Unit {
    run {
        when (page) {
            "subagents",
            "agent-team" -> id?.let { onAction(AppAction.OpenAgents(it)) }
            "terminal" -> onAction(AppAction.OpenTerminal)
            "files",
            "artifacts" -> id?.let { onAction(AppAction.OpenWorkspaceFiles(it)) }
            "export" -> id?.let { onAction(AppAction.OpenSessionExport(it)) }
            "jobs" -> id?.let { onAction(AppAction.OpenJobs(it)) }
            "plan-goal" -> onAction(AppAction.ShowGoal)
            "skills" -> id?.let { onAction(AppAction.OpenSkills(it)) }
            "models" -> id?.let { onAction(AppAction.OpenModelPicker(it)) }
            "permission" -> onAction(AppAction.OpenPermissions(id))
            "schedules" -> onAction(AppAction.OpenSchedules)
            "new-chat" -> onAction(AppAction.NewSession)
            "session-detail" ->
                id?.let {
                    mutableState.update { app ->
                        app.copy(
                            openSessionId = it,
                            openSessionRevision = app.openSessionRevision + 1,
                        )
                    }
                    openSession(it)
                }
            else ->
                mutableState.update {
                    it.copy(
                        featurePage = page,
                        featurePageRevision = it.featurePageRevision + 1,
                    )
                }
        }
    }
}

internal fun AppViewModel.handleNativePlugins(action: AppAction): Boolean {
    when (action) {
        is AppAction.NativePluginVisibleTurns -> {
            if (watchedId == action.sessionId) nativePlugins.visibleTurns(action.turns)
            return true
        }
        is AppAction.NativePluginInvoke -> {
            val id = action.scope.sessionId
            if (id == watchedId)
                nativePlugins.invoke(
                    action.action,
                    action.scope,
                    id?.let { composer.input(it).draft },
                    action.message,
                )
            return true
        }
        is AppAction.NativePluginPanel -> {
            nativePlugins.openPanel(action.pkg, action.id)
            return true
        }
        is AppAction.NativePluginPanelAction -> {
            nativePlugins.panelAction(action.action)
            return true
        }
        is AppAction.NativePluginConsent -> {
            nativePlugins.answerConsent(action.allowed)
            return true
        }
        is AppAction.NativePluginSurface -> {
            nativePlugins.setSurface(action.pkg, action.surface, action.enabled)
            return true
        }
        AppAction.CloseNativePluginPanel -> {
            nativePlugins.closePanel()
            return true
        }
        else -> return false
    }
}

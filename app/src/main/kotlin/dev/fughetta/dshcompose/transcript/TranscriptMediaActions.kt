package dev.fughetta.dshcompose.transcript

import android.app.Application
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.connection.connectLocal
import dev.fughetta.dshcompose.connection.connectService
import dev.fughetta.dshcompose.connection.status
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.messages.handlePendingPrompt
import dev.fughetta.dshcompose.revealOwnedSession
import dev.fughetta.dshcompose.sessions.drawerAction
import dev.fughetta.dshcompose.sessions.queueOfflineSend
import dev.fughetta.dshcompose.sessions.refreshSessionAttention
import dev.fughetta.dshcompose.sessions.refreshSessions
import dev.fughetta.dshcompose.sessions.selectDraft
import dev.fughetta.dshcompose.sessions.sendDraft
import dev.fughetta.dshcompose.sessions.sendMessage
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.conversation.ViewReplyUiState
import dev.fughetta.dshcompose.ui.dynamicui.UiReplyLabels
import dev.fughetta.dshcompose.ui.dynamicui.dynamicUiCanAnswer
import dev.fughetta.dshcompose.ui.dynamicui.dynamicUiGenuiActionText
import dev.fughetta.dshcompose.ui.dynamicui.dynamicUiReplyText
import dev.fughetta.dshcompose.ui.dynamicui.dynamicUiResendText
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleTranscriptMedia(action: AppAction): Boolean {
    when (action) {
        is AppAction.AskAi -> {

            selectDraft()
            updateComposer(DRAFT_SESSION_ID, replaceText = true) {
                it.copy(
                    draft = action.text,
                    draftCursor = null,
                    draftSelectionEnd = action.text.length,
                    draftSpans = persistentListOf(),
                )
            }

            return true
        }
        is AppAction.PendingPrompts -> {
            handlePendingPrompt(action.action)
            return true
        }
        is AppAction.RetryTranscriptNotice -> {
            retryTranscriptNotice(action.sessionId, action.messageKey)
            return true
        }
        is AppAction.TranscriptArtifact -> {
            transcriptArtifacts.action(
                action.sessionId,
                action.file,
                action.action,
            )
            return true
        }
        is AppAction.OpenSessionArtifacts -> {
            mutableState.update { it.copy(sessionArtifacts = action.sessionId) }
            return true
        }
        AppAction.CloseSessionArtifacts -> {
            mutableState.update { it.copy(sessionArtifacts = null) }
            return true
        }
        AppAction.CloseTranscriptTable -> {
            transcriptArtifacts.closeTable()
            return true
        }
        is AppAction.TranscriptSheet -> {
            transcriptArtifacts.selectSheet(action.sheet)
            return true
        }
        AppAction.RefreshTranscriptTable -> {
            transcriptArtifacts.refreshTable()
            return true
        }
        is AppAction.Media -> {
            mediaGeneration.action(action.action)
            return true
        }
        is AppAction.OpenMessageMenu -> {
            openMessageMenu(action.sessionId, action.messageKey)
            return true
        }
        is AppAction.MessageOperation -> {
            performMessageOperation(action.operation)
            return true
        }
        AppAction.CloseMessageMenu -> {
            conversationModel.dismissMessage()
            return true
        }
        is AppAction.ClearQuote -> {
            updateComposer(action.sessionId) { it.copy(quote = null) }
            return true
        }
        is AppAction.ReturnToOriginal -> {
            conversations.state.value.chats[action.sessionId]?.forkSourceId?.let { id ->
                client?.let { revealOwnedSession(it, id) }
            }
            return true
        }
        is AppAction.SelectHost -> {
            if (action.host == "local") {
                connectLocal()
                if (
                    runtime.phase ==
                        dev.fughetta.dshcompose.engine.runtime.RuntimeController.Phase.INSTALLED
                )
                    engineControls.action(
                        dev.fughetta.dshcompose.ui.runtime.EngineControlsAction.Start,
                    )
            } else if (action.host.startsWith("compose-")) connectService(action.host)
            return true
        }
        is AppAction.VisibleSession -> {

            if (action.visible) visibleSessionId = action.sessionId
            else if (visibleSessionId == action.sessionId) visibleSessionId = null
            notificationSettings.visibility(foreground, selectedHost, visibleSessionId)
            refreshSessionAttention()

            return true
        }
        is AppAction.ExportReplyImage -> {
            imageExports.start(action.request, action.operation)
            return true
        }
        AppAction.CloseImageExport -> {
            imageExports.dismiss()
            return true
        }
        is AppAction.ManageSession -> {

            val title =
                conversations.state.value.chats[action.sessionId]?.title
                    ?: sessionRepository.get(action.sessionId)?.title.orEmpty()
            sessionsModel.open(action.sessionId, action.operation, title)
            return true
        }
        is AppAction.EditSessionTitle -> {
            sessionsModel.editTitle(action.title)
            return true
        }
        AppAction.DismissSessionDialog -> {
            sessionsModel.dismiss()
            return true
        }
        AppAction.ConfirmSessionOperation -> {
            performSessionOperation()
            return true
        }
        AppAction.LoadLicenses -> {
            viewModelScope.launch {
                if (state.value.licenseDocuments != null) return@launch
                try {
                    val text =
                        withContext(Dispatchers.IO) {
                            val assets = getApplication<Application>().assets
                            listOf(
                                    "licenses/THIRD_PARTY_NOTICES.md",
                                    "licenses/LICENSE",
                                    "fonts/OFL.txt",
                                )
                                .map { path ->
                                    LicenseDocument(
                                        path,
                                        assets.open(path).bufferedReader(Charsets.UTF_8).use {
                                            it.readText()
                                        },
                                    )
                                }
                        }
                    mutableState.update { it.copy(licenseDocuments = text, licensesError = null) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableState.update {
                        it.copy(
                            licensesError =
                                getApplication<Application>()
                                    .getString(UiR.string.licenses_unavailable),
                        )
                    }
                }
            }
            return true
        }
        is AppAction.EditQuestion -> {

            if (
                action.request.connection != connectionNumber ||
                    client
                        ?.requests
                        ?.value
                        ?.questions
                        ?.containsKey(action.request.request.eventId) != true
            )
                return true
            val event = action.request.request.eventId
            questionDrafts[event] =
                questionDrafts[event].orEmpty() + (action.answer.id to action.answer)
            refreshChatRequests(action.sessionId)

            return true
        }
        is AppAction.ChangeDraft -> {

            updateComposer(
                action.sessionId,
                preserveReferenceQuery = true,
                replaceText = true,
            ) {
                it.copy(
                    draft = action.text,
                    draftCursor = null,
                    draftSelectionEnd =
                        listOfNotNull(action.selectionStart, action.selectionEnd).maxOrNull(),
                    draftSpans = action.spans,
                )
            }
            if (action.sessionId != DRAFT_SESSION_ID)
                commands.composer(action.sessionId, action.text, action.composing)
            val start = action.selectionStart
            val end = action.selectionEnd
            if (start != null && end != null)
                references.composer(
                    action.sessionId,
                    action.text,
                    start,
                    end,
                    action.composing ||
                        action.spans.any { start == end && end > it.start && end <= it.end },
                )

            return true
        }
        is AppAction.SelectSession -> {

            val id = action.id
            if (id == null || id == DRAFT_SESSION_ID) selectDraft()
            else {
                mutableState.update {
                    it.copy(
                        openSessionId = id,
                        openSessionRevision = it.openSessionRevision + 1,
                    )
                }
                openSession(id)
            }

            return true
        }
        AppAction.NewSession -> {
            selectDraft()
            return true
        }
        is AppAction.Drawer -> {
            drawerAction(action.action)
            return true
        }
        is AppAction.OpenSession -> {
            openSession(action.sessionId)
            return true
        }
        is AppAction.RemoveAttachment -> {

            if (conversations.state.value.chats[action.sessionId]?.sending == true) return true
            val item =
                composerAttachments.selected(action.sessionId).firstOrNull {
                    it.id == action.attachmentId
                } ?: return true
            composerAttachments.detach(action.sessionId, setOf(item.id))
            connectionSession?.messageAttachments?.forget(setOf(item.id))

            viewModelScope.launch { composerFiles.release(listOf(item)) }

            return true
        }
        else -> {
            viewModelScope.launch {
                val owner = client
                if (owner == null) {
                    if (action == AppAction.RefreshSessions) {
                        connectionIdentity = null
                        status(runtime.status())
                    } else if (action is AppAction.Send && queueOfflineSend(action.sessionId)) {
                        // Queued durably; the reconnect path delivers it.
                    } else
                        mutableState.update {
                            it.copy(
                                error =
                                    getApplication<Application>()
                                        .getString(UiR.string.engine_not_connected),
                            )
                        }
                    return@launch
                }
                try {
                    when (action) {
                        is AppAction.LoadHistory -> conversationModel.loadHistory(action.sessionId)
                        AppAction.RefreshSessions -> refreshSessions(owner)
                        is AppAction.ViewRequest -> {
                            val chat =
                                conversations.state.value.chats[action.sessionId] ?: return@launch
                            val row =
                                chat.messages.firstOrNull { it.key == action.messageKey }
                                    ?: return@launch
                            if (
                                action.generation != state.value.imageGeneration ||
                                    chat.sending ||
                                    chat.viewReply?.messageKey == row.key ||
                                    !dynamicUiCanAnswer(
                                        row,
                                        chat.messageOrder,
                                        chat.running || chat.syncing,
                                    )
                            )
                                return@launch
                            updateChat(action.sessionId) { it.copy(sending = true, error = null) }
                            var acknowledged = false
                            try {
                                val application = getApplication<Application>()
                                val labels =
                                    UiReplyLabels(
                                        AppStrings.get(
                                            application,
                                            UiR.string.view_reply_select,
                                        ),
                                        AppStrings.get(
                                            application,
                                            UiR.string.view_reply_other,
                                        ),
                                        AppStrings.get(application, UiR.string.confirm),
                                        AppStrings.get(application, UiR.string.cancel),
                                    )
                                val text =
                                    withContext(Dispatchers.Default) {
                                        when (action) {
                                            is AppAction.ReplyToView ->
                                                dynamicUiReplyText(
                                                    row,
                                                    action.source,
                                                    action.path,
                                                    action.selected,
                                                    action.freetext,
                                                    action.confirmed,
                                                    labels,
                                                )
                                            is AppAction.AskViewResend ->
                                                dynamicUiResendText(
                                                    row,
                                                    action.source,
                                                    { AppStrings.get(application, it) },
                                                ) {
                                                    AppStrings.get(
                                                        application,
                                                        UiR.string.view_resend_message,
                                                        it,
                                                    )
                                                }
                                            is AppAction.GenuiAction ->
                                                dynamicUiGenuiActionText(
                                                    row,
                                                    action.source,
                                                    action.input,
                                                ) { name, data ->
                                                    AppStrings.get(
                                                        application,
                                                        UiR.string.view_genui_action,
                                                        name,
                                                        data,
                                                    )
                                                }
                                        }
                                    }
                                val current = conversations.state.value.chats[action.sessionId]
                                if (
                                    client !== owner ||
                                        action.generation != state.value.imageGeneration ||
                                        current == null
                                )
                                    return@launch
                                val currentRow = current.messages.firstOrNull { it.key == row.key }
                                if (
                                    text == null ||
                                        currentRow == null ||
                                        currentRow.text != row.text ||
                                        currentRow.tool?.meta != row.tool?.meta ||
                                        !dynamicUiCanAnswer(
                                            currentRow,
                                            current.messageOrder,
                                            current.running || current.syncing,
                                        )
                                ) {
                                    updateChat(action.sessionId) {
                                        it.copy(
                                            error =
                                                AppStrings.get(
                                                    application,
                                                    UiR.string.view_expired,
                                                ),
                                        )
                                    }
                                    return@launch
                                }
                                updateChat(action.sessionId) {
                                    it.copy(viewReply = ViewReplyUiState(row.key, text, true))
                                }
                                hosts.prompt(
                                    owner,
                                    action.sessionId,
                                    text,
                                    timeZone = java.util.TimeZone.getDefault().id,
                                )
                                acknowledged = true
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                if (
                                    client === owner &&
                                        action.generation == state.value.imageGeneration
                                )
                                    updateChat(action.sessionId) {
                                        it.copy(
                                            error =
                                                AppStrings.get(
                                                    getApplication<Application>(),
                                                    UiR.string.view_send_failed,
                                                ),
                                        )
                                    }
                            } finally {
                                if (
                                    client === owner &&
                                        action.generation == state.value.imageGeneration
                                )
                                    updateChat(action.sessionId) {
                                        it.copy(
                                            sending = false,
                                            viewReply =
                                                if (acknowledged) it.viewReply?.copy(busy = false)
                                                else null,
                                        )
                                    }
                            }
                        }
                        is AppAction.Send -> {
                            if (action.sessionId == DRAFT_SESSION_ID) sendDraft(owner)
                            else sendMessage(owner, action.sessionId)
                        }
                        is AppAction.RespondQuestion -> {
                            val responses = owner.repositories?.notifications ?: return@launch
                            val generation = responses.generation
                            val requests = owner.requests.value
                            val question = action.request
                            val eventId = question.request.eventId
                            val pending = requests.questions[eventId]
                            if (
                                question.connection != connectionNumber ||
                                    generation?.number != question.generation ||
                                    requests.generation != generation ||
                                    pending?.sessionId != action.sessionId
                            ) {
                                updateChat(action.sessionId) {
                                    it.copy(
                                        error =
                                            getApplication<Application>()
                                                .getString(UiR.string.approval_expired),
                                    )
                                }
                                return@launch
                            }
                            if (!requestResponses.add(eventId)) return@launch
                            refreshChatRequests(action.sessionId)
                            try {
                                val current = pending
                                val answers =
                                    current.questions.map {
                                        questionDrafts[eventId]?.get(it.id) ?: QuestionAnswer(it.id)
                                    }
                                if (action.reject) responses.rejectQuestion(eventId)
                                else responses.answerQuestion(eventId, answers)
                                if (question.connection == connectionNumber)
                                    questionDrafts.remove(eventId)
                            } finally {
                                if (question.connection == connectionNumber) {
                                    requestResponses.remove(eventId)
                                    refreshChatRequests(action.sessionId)
                                }
                            }
                        }
                        is AppAction.RespondApproval -> {
                            val responses = owner.repositories?.notifications ?: return@launch
                            val generation = responses.generation
                            val requests = owner.requests.value
                            val approval = action.approval
                            val pending = requests.approvals[approval.eventId]
                            if (
                                approval.connection != connectionNumber ||
                                    generation?.number != approval.generation ||
                                    requests.generation != generation ||
                                    pending?.sessionId != action.sessionId
                            ) {
                                updateChat(action.sessionId) {
                                    it.copy(
                                        error =
                                            getApplication<Application>()
                                                .getString(UiR.string.approval_expired),
                                    )
                                }
                                return@launch
                            }
                            if (!requestResponses.add(approval.eventId)) return@launch
                            refreshChatRequests(action.sessionId)
                            try {
                                responses.respondApproval(
                                    approval.eventId,
                                    if (action.allow) "allowed-once" else "rejected",
                                )
                            } finally {
                                if (approval.connection == connectionNumber) {
                                    requestResponses.remove(approval.eventId)
                                    refreshChatRequests(action.sessionId)
                                }
                            }
                        }
                        is AppAction.StopTurn ->
                            owner.repositories?.notifications?.cancel(action.sessionId)
                        else -> Unit
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (client === owner) {
                        if (action is AppAction.Send)
                            updateChat(action.sessionId) { it.copy(error = error.message) }
                        else if (action is AppAction.RespondApproval)
                            updateChat(action.sessionId) { it.copy(error = error.message) }
                        else if (action is AppAction.RespondQuestion)
                            updateChat(action.sessionId) { it.copy(error = error.message) }
                        else mutableState.update { it.copy(error = error.message) }
                    }
                }
            }
            return true
        }
    }
}

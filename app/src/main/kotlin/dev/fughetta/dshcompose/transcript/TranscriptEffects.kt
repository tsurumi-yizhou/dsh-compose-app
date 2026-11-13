package dev.fughetta.dshcompose.transcript

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.attachments.AttachmentImportException
import dev.fughetta.dshcompose.domain.attachments.AttachmentLimits
import dev.fughetta.dshcompose.domain.attachments.AttachmentSource
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.messages.QuotedText
import dev.fughetta.dshcompose.domain.messages.normalizeQuote
import dev.fughetta.dshcompose.domain.messages.splitQuote
import dev.fughetta.dshcompose.domain.sessions.ForkSessionUseCase
import dev.fughetta.dshcompose.domain.sessions.SessionOperationException
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.revealOwnedSession
import dev.fughetta.dshcompose.sessions.refreshSessions
import dev.fughetta.dshcompose.sessions.sendMessage
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun AppViewModel.retryTranscriptNotice(id: String, key: String): Unit {
    run {
        val owner = client ?: return
        val generation = owner.state.value.generation ?: return
        val transcript = activeConversation?.takeIf { watchedId == id } ?: return
        viewModelScope.launch {
            val plan = transcript.retryForNotice(key) ?: return@launch
            val chat = conversations.state.value.chats[id] ?: return@launch
            if (
                client !== owner ||
                    owner.state.value.generation != generation ||
                    activeConversation !== transcript ||
                    chat.running ||
                    chat.syncing ||
                    chat.sending ||
                    chat.inbox.isNotEmpty()
            )
                return@launch
            val pendingOwner = PendingPromptsOwner(selectedHost, generation.home, id)
            if (pendingPrompts.state.value.values.any { it.owner == pendingOwner }) return@launch
            try {
                sendMessage(
                    owner,
                    id,
                    overrideText =
                        plan.repeatText
                            ?: getApplication<Application>()
                                .getString(UiR.string.transcript_continue_prompt),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (client === owner) updateChat(id) { it.copy(error = error.message) }
            }
        }
    }
}

internal suspend fun AppViewModel.handleMediaEffect(
    dispatch: dev.fughetta.dshcompose.ui.media.MediaDispatch,
): Unit {
    run {
        if (dispatch.hostKey != selectedHost) return
        val owner = client ?: return
        if (
            mediaRepository.connection.value !== dispatch.owner ||
                !dispatch.owner.active ||
                owner.state.value.generation != dispatch.muxGeneration
        )
            return
        val generation = owner.state.value.generation ?: return
        val number = connectionNumber
        val app = getApplication<Application>()
        fun current() =
            client === owner &&
                connectionNumber == number &&
                owner.state.value.generation == generation
        when (val effect = dispatch.effect) {
            dev.fughetta.dshcompose.ui.media.MediaEffect.OpenPlugins ->
                mutableState.update {
                    it.copy(
                        featurePage = "plugins",
                        featurePageRevision = it.featurePageRevision + 1,
                    )
                }
            is dev.fughetta.dshcompose.ui.media.MediaEffect.ImageExport ->
                imageExports.start(
                    dev.fughetta.dshcompose.ui.markdown.ReplyImageRequest(
                        effect.sessionId,
                        effect.item.path,
                        number,
                    ),
                    effect.operation,
                )
            is dev.fughetta.dshcompose.ui.media.MediaEffect.GoConversation ->
                revealOwnedSession(owner, effect.sessionId)
            is dev.fughetta.dshcompose.ui.media.MediaEffect.Message -> {
                val text =
                    if (effect.code == "media/saved")
                        AppStrings.get(app, UiR.string.media_export_saved)
                    else effect.code
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show()
            }
            is dev.fughetta.dshcompose.ui.media.MediaEffect.ShareFile ->
                try {
                    val file = File(effect.path)
                    val uri =
                        androidx.core.content.FileProvider.getUriForFile(
                            app,
                            "${app.packageName}.files",
                            file,
                        )
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType(effect.mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply {
                                clipData =
                                    android.content.ClipData.newUri(
                                        app.contentResolver,
                                        effect.name,
                                        uri,
                                    )
                            }
                    app.startActivity(
                        Intent.createChooser(
                                send,
                                AppStrings.get(app, UiR.string.message_action_share),
                            )
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            ),
                    )
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = "media/share-failed") }
                }
            is dev.fughetta.dshcompose.ui.media.MediaEffect.Compose -> {
                val original = watchedId
                if (dispatch.sessionId != original) return
                var staged: ComposerFile? = null
                var transferred = false
                try {
                    imports.withLock {
                        val reference = effect.reference
                        if (reference != null) {
                            val source =
                                effect.referenceSessionId
                                    ?: throw OperationFailure("media/reference-session-missing")
                            val temporary =
                                withContext(Dispatchers.IO) {
                                    File.createTempFile(
                                        "media-reference-",
                                        ".image",
                                        app.cacheDir,
                                    )
                                }
                            try {
                                withContext(Dispatchers.IO) {
                                    temporary.outputStream().use {
                                        requireNotNull(
                                                connectionSession
                                                    ?.takeIf { it === owner }
                                                    ?.repositories
                                                    ?.files,
                                            )
                                            .copy(
                                                source,
                                                reference.path,
                                                it::write,
                                                maximumBytes = AttachmentLimits.MAX_IMAGE_BYTES,
                                            )
                                    }
                                }
                                val type =
                                    withContext(Dispatchers.IO) {
                                        mediaRepository.imageMime(temporary.absolutePath)
                                    } ?: throw AttachmentImportException("unsupported_image")
                                staged =
                                    composerFiles.importFile(
                                        AttachmentSource(
                                            Uri.fromFile(temporary).toString(),
                                            reference.name,
                                            type,
                                        ),
                                        AttachmentLimits.MAX_IMAGE_BYTES,
                                    )
                            } finally {
                                withContext(NonCancellable + Dispatchers.IO) { temporary.delete() }
                            }
                        }
                        if (!current() || watchedId != original) return@withLock
                        val workspace = state.value.workspaces.selectedId
                        val id =
                            original
                                ?: (owner.repositories?.sessions
                                        ?: throw OperationFailure("connection-lost"))
                                    .create(
                                        cwd = hostWorkingDirectory.takeIf { workspace == null },
                                        workspaceId = workspace,
                                    )
                        if (!current() || watchedId != original) return@withLock
                        staged?.let { composerAttachments.add(id, listOf(it)) }
                        transferred = true
                        mediaGeneration.mode(id, effect.parameters)
                        mediaGeneration.action(
                            dev.fughetta.dshcompose.ui.media.MediaAction.ClosePreview,
                        )
                        mediaGeneration.action(
                            dev.fughetta.dshcompose.ui.media.MediaAction.CloseWorks,
                        )
                        updateComposer(id) { chat ->
                            chat.copy(
                                draft = effect.prompt,
                                quote = null,
                                draftCursor = effect.prompt.length,
                                draftSpans = persistentListOf(),
                            )
                        }
                        updateChat(id) { it.copy(error = null) }
                        revealOwnedSession(owner, id)
                        if (effect.send) onAction(AppAction.Send(id))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (current()) mutableState.update { it.copy(error = error.message) }
                } finally {
                    if (!transferred)
                        withContext(NonCancellable) {
                            staged?.let { composerFiles.release(listOf(it)) }
                        }
                }
            }
        }
    }
}

internal fun AppViewModel.openMessageMenu(id: String, key: String): Unit {
    run { conversationModel.openMessage(id, key) }
}

internal fun AppViewModel.performMessageOperation(
    operation: dev.fughetta.dshcompose.ui.messages.MessageOperation,
): Unit {
    run {
        val selectedMenu = conversationModel.messageMenu.value?.takeIf { !it.busy } ?: return
        val owner = client ?: return
        val operations = owner.repositories?.sessions ?: return
        val generation = owner.state.value.generation ?: return
        val transcript =
            activeConversation?.takeIf { watchedId == selectedMenu.sessionId } ?: return
        val app = getApplication<Application>()
        conversationModel.performMessage(operation) {
            val menu = this.menu
            when (operation) {
                MessageOperation.Copy -> {
                    val clipboard =
                        app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("", menu.row.text),
                    )
                    return@performMessage
                }
                MessageOperation.Share -> {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, menu.row.text)
                    app.startActivity(
                        Intent.createChooser(
                                send,
                                AppStrings.get(app, UiR.string.message_action_share),
                            )
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    return@performMessage
                }
                MessageOperation.Quote -> {
                    val quote = normalizeQuote(menu.row.text)
                    updateComposer(menu.sessionId) {
                        it.copy(
                            quote = quote.takeIf(String::isNotBlank),
                            draftCursor = it.draft.length,
                        )
                    }
                    return@performMessage
                }
                else -> Unit
            }
            val staged = mutableListOf<ComposerFile>()
            var transferred = false
            fun current() =
                isCurrent() &&
                    client === owner &&
                    owner.state.value.generation == generation &&
                    activeConversation === transcript &&
                    watchedId == menu.sessionId
            try {
                val prompt =
                    if (
                        operation == dev.fughetta.dshcompose.ui.messages.MessageOperation.Regenerate
                    )
                        menu.regeneratePrompt
                    else menu.row
                if (operation == dev.fughetta.dshcompose.ui.messages.MessageOperation.Regenerate) {
                    val chat =
                        conversations.state.value.chats[menu.sessionId] ?: return@performMessage
                    if (
                        chat.running ||
                            chat.sending ||
                            chat.messages.lastOrNull { it.role != ChatRole.Notice }?.key !=
                                menu.row.key
                    )
                        throw OperationFailure("message/regenerate-unavailable")
                    val forkState =
                        operations.forkState(menu.sessionId)
                            ?: throw SessionOperationException("message/projections-unavailable")
                    if (forkState.queuedItemIds.isNotEmpty())
                        throw SessionOperationException("message/queued-prompts")
                    if (!current()) return@performMessage
                    val images =
                        connectionSession?.takeIf { it === owner }?.messageAttachments
                            ?: return@performMessage
                    staged.addAll(
                        RestorePromptImagesUseCase(
                            images,
                            composerFiles::importImage,
                            composerFiles::release,
                        )(
                            menu.sessionId,
                            prompt!!.attachments,
                        ),
                    )
                }
                val cut =
                    if (operation == dev.fughetta.dshcompose.ui.messages.MessageOperation.Branch)
                        menu.branchCut
                    else {
                        val seq = prompt?.seq ?: throw OperationFailure("message/missing-sequence")
                        if (seq <= 0) throw OperationFailure("message/earlier-history-required")
                        transcript.branchPointBefore(seq)
                    } ?: throw OperationFailure("message/earlier-history-required")
                if (!current()) return@performMessage
                val outcome =
                    ForkSessionUseCase(operations)(
                        menu.sessionId,
                        cut,
                        conversations.state.value.chats[menu.sessionId]?.title,
                    )
                if (!current()) return@performMessage
                val child = outcome.sessionId
                val edited =
                    if (operation == dev.fughetta.dshcompose.ui.messages.MessageOperation.Branch)
                        QuotedText(null, "")
                    else splitQuote(mediaGeneration.restorePrompt(child, prompt!!.text))
                composerAttachments.add(child, staged)
                transferred = true
                updateComposer(child) {
                    it.copy(
                        draft = edited.body,
                        quote = edited.quote,
                        draftCursor = edited.body.length,
                    )
                }
                updateChat(child) {
                    it.copy(
                        forkSourceId = menu.sessionId,
                        forkWarning =
                            when {
                                outcome.cleanupUnknown || outcome.leftQueued > 0 ->
                                    AppStrings.get(app, UiR.string.message_fork_queue_warning)
                                operation ==
                                    dev.fughetta.dshcompose.ui.messages.MessageOperation.Edit &&
                                    prompt?.attachments?.isNotEmpty() == true ->
                                    AppStrings.get(app, UiR.string.message_fork_reattach)
                                else -> null
                            },
                    )
                }
                mutableState.update {
                    it.copy(
                        openSessionId = child,
                        openSessionRevision = it.openSessionRevision + 1,
                    )
                }
                complete()
                openSession(child)
                viewModelScope.launch {
                    try {
                        refreshSessions(owner)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (client === owner) mutableState.update { it.copy(error = error.message) }
                    }
                }
                if (
                    operation == MessageOperation.Regenerate &&
                        !outcome.cleanupUnknown &&
                        outcome.leftQueued == 0
                )
                    onAction(AppAction.Send(child))
            } finally {
                if (!transferred) withContext(NonCancellable) { composerFiles.release(staged) }
            }
        }
    }
}

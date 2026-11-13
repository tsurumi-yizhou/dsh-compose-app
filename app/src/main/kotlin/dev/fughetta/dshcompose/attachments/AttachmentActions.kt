package dev.fughetta.dshcompose.attachments

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.AppViewModel.IncomingAttachment
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.attachments.AttachmentImportException
import dev.fughetta.dshcompose.domain.attachments.AttachmentLimits
import dev.fughetta.dshcompose.domain.attachments.AttachmentSource
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun AppViewModel.importPickedFiles(uris: List<Uri>, onComplete: () -> Unit = {}): Unit {
    run {
        val pending =
            pickerSession
                ?: run {
                    onComplete()
                    return
                }
        pickerSession = null
        if (pending.second != connectionNumber || pending.first in retiredSessions) {
            onComplete()
            return
        }
        val id = pending.first
        viewModelScope.launch {
            try {
                importFiles(id, uris.map { IncomingAttachment(it) }, pending.second)
            } finally {
                onComplete()
            }
        }
    }
}

internal suspend fun AppViewModel.importFiles(
    id: String,
    incoming: List<IncomingAttachment>,
    connection: Long = connectionNumber,
): Unit {
    run {
        if (connection != connectionNumber || id in retiredSessions) return
        updateChat(id) { it.copy(error = null) }
        imports.withLock {
            if (id in retiredSessions || connection != connectionNumber) return
            for (source in incoming) {
                val files = composerAttachments.selected(id)
                try {
                    if (files.size >= AttachmentLimits.MAX_FILES)
                        throw AttachmentImportException("too_many")
                    val item =
                        composerFiles.importFile(
                            AttachmentSource(
                                source.uri.toString(),
                                source.name,
                                source.mediaType,
                            ),
                            AttachmentLimits.MAX_TOTAL_BYTES - files.sumOf { it.bytes },
                        )
                    if (id in retiredSessions || connection != connectionNumber) {
                        composerFiles.release(listOf(item))
                        return
                    }
                    try {
                        composerAttachments.add(id, listOf(item))
                    } catch (error: Exception) {
                        withContext(NonCancellable) { composerFiles.release(listOf(item)) }
                        throw error
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message =
                        when ((error as? AttachmentImportException)?.code) {
                            "too_many" -> UiR.string.attachment_too_many
                            "too_large" -> UiR.string.attachment_too_large
                            else -> UiR.string.attachment_unreadable
                        }
                    updateChat(id) {
                        it.copy(error = getApplication<Application>().getString(message))
                    }
                }
            }
        }
    }
}

internal fun AppViewModel.importShares(): Unit {
    run {
        if (importingShares?.isActive == true) return
        val owner = sessionRepository.connection.value ?: return
        importingShares = viewModelScope.launch {
            while (sharedInbox.isNotEmpty() && sessionRepository.connection.value === owner) {
                val share = sharedInbox.first()
                try {
                    val id = watchedId ?: owner.create(cwd = hostWorkingDirectory)
                    if (
                        sessionRepository.connection.value !== owner ||
                            conversations.state.value.chats[id]?.sending == true
                    )
                        return@launch
                    val text = share["text"] as? String
                    if (!text.isNullOrEmpty())
                        updateComposer(id) {
                            it.copy(
                                draftSelectionEnd = null,
                                draftCursor = null,
                                draft =
                                    listOf(it.draft, text)
                                        .filter(String::isNotBlank)
                                        .joinToString("\n\n"),
                            )
                        }
                    val incoming =
                        (share["files"] as? List<*>).orEmpty().mapNotNull { raw ->
                            val file = raw as? Map<*, *> ?: return@mapNotNull null
                            val path = file["path"] as? String ?: return@mapNotNull null
                            IncomingAttachment(
                                Uri.fromFile(File(path)),
                                file["name"] as? String,
                                file["mime"] as? String,
                            )
                        }
                    importFiles(id, incoming)
                    if (sessionRepository.connection.value !== owner) return@launch
                    if (id in retiredSessions) continue
                    if ((share["skipped"] as? List<*>)?.isNotEmpty() == true)
                        updateChat(id) {
                            it.copy(
                                error =
                                    getApplication<Application>()
                                        .getString(UiR.string.attachment_unreadable),
                            )
                        }
                    sharedInbox.removeFirst()
                    mutableState.update { it.copy(openSessionId = id) }
                    openSession(id)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (sessionRepository.connection.value === owner)
                        mutableState.update { it.copy(error = error.message) }
                    return@launch
                }
            }
        }
    }
}

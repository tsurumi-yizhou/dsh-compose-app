package dev.fughetta.dshcompose.di

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.data.messages.EngineAttachmentsRepository
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.files.DirectoryPickerViewModel
import dev.fughetta.dshcompose.files.ReferencesViewModel
import dev.fughetta.dshcompose.files.WorkspaceFileExporter
import dev.fughetta.dshcompose.files.WorkspaceFilesViewModel
import dev.fughetta.dshcompose.files.WorkspacesViewModel
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.messages.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createComposerFiles():
    dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository = run {
    EngineAttachmentsRepository(app)
}

internal fun AppViewModel.createSessionArchives() = run {
    dev.fughetta.dshcompose.files.NativeSessionArchives(app)
}

internal fun AppViewModel.createSessionExport() = run {
    ownedModel {
        dev.fughetta.dshcompose.files.SessionExportViewModel(
            null,
            destination = sessionArchives::create,
            legacyPermission = { android.os.Build.VERSION.SDK_INT <= 28 },
            hasPermission = {
                app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            publish = { export -> mutableState.update { it.copy(sessionExport = export) } },
        )
    }
}

internal fun AppViewModel.createReferences() = run {
    ownedModel {
        ReferencesViewModel(
            null,
            message = { AppStrings.get(app, it) },
            publish = { picker -> mutableState.update { it.copy(references = picker) } },
            draftCandidates = { query ->
                sessionRepository.state.value.allSessions
                    .filter {
                        !it.blank &&
                            it.sessionId.matches(Regex("[A-Za-z0-9_-]+")) &&
                            (it.title.orEmpty().contains(query, ignoreCase = true) ||
                                it.sessionId.contains(query, ignoreCase = true))
                    }
                    .map { session ->
                        val title = session.title ?: session.sessionId
                        val escaped =
                            title
                                .replace("\\", "\\\\")
                                .replace("]", "\\]")
                                .replace("\n", " ")
                                .replace("\r", " ")
                        dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate(
                            session.sessionId,
                            title,
                            "@[$escaped](dsh-session:${session.sessionId})",
                            false,
                            session.cwd,
                        )
                    }
            },
            insert = { picker, insertion ->
                var inserted = false
                updateComposer(picker.sessionId) { chat ->
                    inserted =
                        chat.draft == picker.draft &&
                            conversations.state.value.chats[picker.sessionId]?.sending != true
                    if (inserted) {
                        val result =
                            insertReferenceDraft(
                                chat.draft,
                                chat.draftSpans,
                                picker.start,
                                picker.end,
                                insertion,
                            )
                        chat.copy(
                            draft = result.input.text,
                            draftCursor = result.input.end,
                            draftSpans = result.spans,
                        )
                    } else chat
                }
                if (inserted && insertion.kind == ReferenceSpanKind.Conversation)
                    pickedReferenceLabels.update { it.adding(insertion.label) }
                inserted
            },
        )
    }
}

internal fun AppViewModel.createWorkspaceExporter() = run {
    WorkspaceFileExporter(app, viewModelScope) { progress ->
        mutableState.update { it.copy(workspaceExport = progress) }
    }
}

internal fun AppViewModel.createWorkspaceFiles() = run {
    ownedModel {
        WorkspaceFilesViewModel(
            null,
            message = { AppStrings.get(app, it) },
            previewDirectory = File(app.cacheDir, "workspace-previews"),
            publish = { browser -> mutableState.update { it.copy(workspaceFiles = browser) } },
        )
    }
}

internal fun AppViewModel.createReplyLinks() = run {
    ownedModel {
        dev.fughetta.dshcompose.files.ReplyLinksViewModel(
            null,
            message = { AppStrings.get(app, it) },
            publish = { link -> mutableState.update { it.copy(replyLink = link) } },
            openFile = { session, path -> workspaceFiles.open(session, path) },
            openPage = ::openPageLink,
        )
    }
}

internal fun AppViewModel.createWorkspaces() = run {
    ownedModel {
        WorkspacesViewModel(
            null,
            message = { AppStrings.get(app, it) },
            publish = { workspaces -> mutableState.update { it.copy(workspaces = workspaces) } },
            preferences = dev.fughetta.dshcompose.settings.WorkspacePreferencesStore(app),
            onRestored = { id ->
                if (sessionRepository.get(id) != null) {
                    mutableState.update {
                        it.copy(
                            openSessionId = id,
                            openSessionRevision = it.openSessionRevision + 1,
                        )
                    }
                    openSession(id)
                }
            },
        )
    }
}

internal fun AppViewModel.createDirectoryPicker() = run {
    ownedModel {
        DirectoryPickerViewModel(
            null,
            message = { AppStrings.get(app, it) },
            publish = { picker -> mutableState.update { it.copy(directoryPicker = picker) } },
            onCreated = { id ->
                mutableState.update { it.copy(openSessionId = id) }
                openSession(id)
            },
            onWorkspace = { workspace -> workspaces.select(workspace.id) },
            registerWorkspace = { ports, path -> workspaces.register(ports, path) },
            onDirectoryChosen = { path -> workspaces.rememberDirectory(path) },
        )
    }
}

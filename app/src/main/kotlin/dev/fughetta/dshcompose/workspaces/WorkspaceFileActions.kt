package dev.fughetta.dshcompose.workspaces

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleWorkspacesFiles(action: AppAction): Boolean {
    when (action) {
        is AppAction.OpenSessionExport -> {
            sessionExport.open(action.sessionId)
            return true
        }
        is AppAction.ExportSession -> {
            sessionExport.run(action.operation)
            return true
        }
        AppAction.CloseSessionExport -> {
            sessionExport.dismiss()
            return true
        }
        AppAction.AddWorkspace -> {
            if (state.value.workspaces.connected && !state.value.workspaces.loading)
                directoryPicker.open(
                    registerWorkspace = true,
                    recent = state.value.workspaces.preferences.recent,
                )
            return true
        }
        is AppAction.SelectWorkspace -> {
            workspaces.select(action.id)
            return true
        }
        is AppAction.ShowArchivedSessions -> {
            workspaces.archived(action.show)
            return true
        }
        is AppAction.ManageWorkspace -> {
            workspaces.manage(action.id, action.operation)
            return true
        }
        is AppAction.EditWorkspaceTitle -> {
            workspaces.title(action.title)
            return true
        }
        AppAction.ConfirmWorkspaceOperation -> {
            workspaces.confirm()
            return true
        }
        AppAction.CloseWorkspaceDialog -> {
            workspaces.dismiss()
            return true
        }
        AppAction.ReloadWorkspaces -> {
            workspaces.reload()
            return true
        }
        is AppAction.OpenReplyLink -> {
            if (action.sessionId == watchedId && action.sessionId !in retiredSessions)
                replyLinks.open(action.sessionId, action.generation, action.target)
            return true
        }
        is AppAction.CloseReplyLink -> {
            replyLinks.dismiss(action.id)
            return true
        }
        is AppAction.OpenWorkspaceFiles -> {
            workspaceFiles.open(action.sessionId)
            return true
        }
        is AppAction.BrowseWorkspaceFiles -> {
            workspaceFiles.browse(action.path)
            return true
        }
        AppAction.CloseWorkspaceFiles -> {
            workspaceFiles.dismiss()
            return true
        }
        is AppAction.PreviewWorkspaceFile -> {
            workspaceFiles.preview(action.path)
            return true
        }
        is AppAction.PageWorkspaceText -> {
            workspaceFiles.page(action.next)
            return true
        }
        AppAction.ReloadWorkspaceText -> {
            workspaceFiles.reloadPreview()
            return true
        }
        AppAction.CloseWorkspaceText -> {
            workspaceFiles.closePreview()
            return true
        }
        is AppAction.ObserveWorkspaceFiles -> {
            workspaceFiles.observe(action.observing)
            return true
        }
        is AppAction.SearchWorkspaceFiles -> {
            workspaceFiles.search(action.query)
            return true
        }
        AppAction.RefreshWorkspaceFiles -> {
            workspaceFiles.refresh()
            return true
        }
        AppAction.CloseWorkspaceExport -> {
            workspaceExporter.dismiss()
            return true
        }
        is AppAction.OpenReferences -> {
            conversations.state.value.chats[action.sessionId]
                ?.takeIf { !it.sending }
                ?.let {
                    references.open(
                        action.sessionId,
                        composer.input(action.sessionId).draft,
                        action.start,
                        action.end,
                    )
                }
            return true
        }
        is AppAction.ReferenceQuery -> {
            references.query(action.query)
            return true
        }
        is AppAction.ReferenceType -> {
            references.kind(action.kind)
            return true
        }
        is AppAction.InsertFileReference -> {
            references.chooseFile(action.path)
            return true
        }
        is AppAction.InsertSessionReference -> {
            references.chooseSession(action.sessionId)
            return true
        }
        AppAction.CloseReferences -> {
            references.dismiss()
            return true
        }
        is AppAction.PinWorkspaceLocally -> {
            workspaces.pinLocally(action.id, action.pinned)
            return true
        }
        is AppAction.MoveWorkspace -> {
            workspaces.move(action.id, action.beforeId)
            return true
        }
        is AppAction.ManageWorkspaceSession -> {
            if (sessionRepository.get(action.id) != null)
                workspaces.manageSession(action.id, action.operation)
            return true
        }
        is AppAction.ManageWorkspaceSessions -> {
            workspaces.manageSessions(
                action.ids.filter { sessionRepository.get(it) != null },
                action.operation,
            )
            return true
        }
        is AppAction.StopActivityOnArchive -> {
            workspaces.stopActivity(action.stop)
            return true
        }
        AppAction.ConfirmWorkspaceSession -> {
            workspaces.confirmSession()
            return true
        }
        AppAction.CloseWorkspaceSessionDialog -> {
            workspaces.dismissSession()
            return true
        }
        is AppAction.OpenDirectoryPicker -> {
            directoryPicker.open(
                action.sessionId?.let { sessionRepository.get(it)?.cwd },
                recent = state.value.workspaces.preferences.recent,
            )
            return true
        }
        is AppAction.EditDirectoryPath -> {
            directoryPicker.editPath(action.value)
            return true
        }
        is AppAction.BrowseDirectory -> {
            directoryPicker.browse(action.path)
            return true
        }
        is AppAction.FilterDirectories -> {
            directoryPicker.query(action.query)
            return true
        }
        is AppAction.ShowHiddenDirectories -> {
            directoryPicker.hidden(action.show)
            return true
        }
        AppAction.ChooseDirectory -> {
            directoryPicker.choose()
            return true
        }
        AppAction.CloseDirectoryPicker -> {
            directoryPicker.dismiss()
            return true
        }
        AppAction.OpenCreateDirectory -> {
            directoryPicker.openCreate()
            return true
        }
        is AppAction.EditNewDirectory -> {
            directoryPicker.folderName(action.name)
            return true
        }
        AppAction.ConfirmCreateDirectory -> {
            directoryPicker.create()
            return true
        }
        AppAction.CloseCreateDirectory -> {
            directoryPicker.closeCreate()
            return true
        }
        else -> return false
    }
}

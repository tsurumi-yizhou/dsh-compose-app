@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.ui.sessions.DrawerUiState
import dev.fughetta.dshcompose.ui.sessions.SessionOperation

@Composable
internal fun ViewOptions(
    state: AppState,
    drawerUi: DrawerUiState,
    loading: Boolean,
    onAction: (AppAction) -> Unit,
    onNewFolder: () -> Unit,
    onManageFolders: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.drawer_view_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val created = drawerUi.view.sort == DrawerSort.Created
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_sort_created)) },
                leadingIcon = { Checkbox(created, null) },
                onClick = {
                    open = false
                    onAction(
                        AppAction.Drawer(
                            DrawerAction.Sort(
                                if (created) DrawerSort.Activity else DrawerSort.Created,
                            ),
                        ),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_select)) },
                onClick = {
                    open = false
                    onAction(AppAction.Drawer(DrawerAction.StartSelection()))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_folder_new)) },
                onClick = {
                    open = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_folders_manage)) },
                onClick = {
                    open = false
                    onManageFolders()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_archived)) },
                leadingIcon = { Checkbox(state.workspaces.showArchived, null) },
                onClick = {
                    open = false
                    onAction(AppAction.ShowArchivedSessions(!state.workspaces.showArchived))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.refresh)) },
                enabled = !loading,
                onClick = {
                    open = false
                    onAction(AppAction.RefreshSessions)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.directory_choose)) },
                onClick = {
                    open = false
                    onAction(AppAction.OpenDirectoryPicker())
                },
            )
        }
    }
}

@Composable
internal fun SessionMenu(
    expanded: Boolean,
    session: SessionSummary,
    state: AppState,
    drawerUi: DrawerUiState,
    onDismiss: () -> Unit,
    onAction: (AppAction) -> Unit,
    onSelect: () -> Unit,
    onArchive: () -> Unit,
    onNewFolder: () -> Unit,
) {
    val id = session.sessionId
    val workspaces = state.workspaces
    val writable = workspaces.connected && !workspaces.loading && !workspaces.writing
    val pinned = id in workspaces.pinned
    val archived = id in workspaces.archived
    var folders by remember { mutableStateOf(false) }
    ContextMenu(
        expanded = expanded,
        onDismissRequest = {
            folders = false
            onDismiss()
        },
    ) {
        if (folders) {
            for (folder in drawerUi.view.folders) DropdownMenuItem(
                text = { Text(folder.name) },
                onClick = {
                    folders = false
                    onDismiss()
                    onAction(AppAction.Drawer(DrawerAction.MoveSessions(listOf(id), folder.id)))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_folder_new)) },
                onClick = {
                    folders = false
                    onDismiss()
                    onNewFolder()
                },
            )
            if (drawerUi.view.folderOf.containsKey(id))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_move_out)) },
                    onClick = {
                        folders = false
                        onDismiss()
                        onAction(AppAction.Drawer(DrawerAction.MoveSessions(listOf(id), null)))
                    },
                )
        } else {
            if (!archived)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (pinned) R.string.drawer_unpin else R.string.drawer_pin,
                            ),
                        )
                    },
                    enabled = writable,
                    onClick = {
                        onDismiss()
                        onAction(
                            AppAction.ManageWorkspaceSession(
                                id,
                                if (pinned) WorkspaceSessionOperation.Unpin
                                else WorkspaceSessionOperation.Pin,
                            ),
                        )
                    },
                )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_session)) },
                onClick = {
                    onDismiss()
                    onAction(AppAction.ManageSession(id, SessionOperation.Rename))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_move)) },
                onClick = { folders = true },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (archived) R.string.drawer_restore
                            else R.string.drawer_archive_selected,
                        ),
                    )
                },
                enabled = writable,
                onClick = {
                    onDismiss()
                    if (archived)
                        onAction(
                            AppAction.ManageWorkspaceSession(id, WorkspaceSessionOperation.Restore),
                        )
                    else onArchive()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_select)) },
                onClick = {
                    onDismiss()
                    onSelect()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete_session),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onDismiss()
                    onAction(AppAction.Drawer(DrawerAction.RequestDeletion(listOf(id))))
                },
            )
        }
    }
}

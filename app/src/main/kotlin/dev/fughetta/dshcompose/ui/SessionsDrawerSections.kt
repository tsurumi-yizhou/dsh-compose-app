@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.sessions.SessionDeletionState

@Composable
internal fun SelectionHeader(count: Int, onAll: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
        }
        Text(
            stringResource(R.string.drawer_selected, count),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onAll) { Text(stringResource(R.string.drawer_select_all)) }
    }
}

@Composable
internal fun SelectionBar(
    count: Int,
    folders: List<DrawerFolder>,
    onMove: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Box {
            TextButton(onClick = { menu = true }, enabled = count > 0) {
                Text(stringResource(R.string.drawer_move))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (folder in folders) DropdownMenuItem(
                    text = { Text(folder.name) },
                    onClick = {
                        menu = false
                        onMove(folder.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_folder_new)) },
                    onClick = {
                        menu = false
                        onNewFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_move_out)) },
                    onClick = {
                        menu = false
                        onMove(null)
                    },
                )
            }
        }
        TextButton(onClick = onPin, enabled = count > 0) {
            Text(stringResource(R.string.drawer_pin_selected))
        }
        TextButton(onClick = onArchive, enabled = count > 0) {
            Text(stringResource(R.string.drawer_archive_selected))
        }
        TextButton(
            onClick = onDelete,
            enabled = count > 0,
            colors =
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.drawer_delete))
        }
    }
}

/** The current host, which opens the host sheet, and settings. */
@Composable
internal fun IdentityBar(
    state: AppState,
    navigation: DrawerNavigation,
    onAction: (AppAction) -> Unit,
) {
    var switching by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostPill(state, { switching = true }, Modifier.weight(1f))
        IconButton(onClick = navigation.onSettings) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
        }
    }
    if (switching)
        HostSheet(
            state,
            onPick = { host ->
                switching = false
                // Open local installation details directly from the host row.
                if (
                    host == "local" &&
                        state.engineControls.phase in setOf("notInstalled", "installing", "error")
                )
                    navigation.onEngine()
                else if (host != state.hostIdentity || host.startsWith("compose-"))
                    onAction(AppAction.SelectHost(host))
            },
            onManage = {
                switching = false
                navigation.onHostServices()
            },
            onDismiss = { switching = false },
        )
}

@Composable
internal fun DeletionDialogs(
    deletion: SessionDeletionState,
    onAction: (AppAction) -> Unit,
) {
    fun drawer(action: DrawerAction) = onAction(AppAction.Drawer(action))
    if (deletion.confirming)
        AlertDialog(
            onDismissRequest = { if (!deletion.busy) drawer(DrawerAction.DismissDeletion) },
            title = { Text(stringResource(R.string.delete_session)) },
            text = { Text(stringResource(R.string.delete_session_details)) },
            confirmButton = {
                TextButton(
                    enabled = !deletion.busy,
                    onClick = { drawer(DrawerAction.ConfirmDeletion) },
                ) {
                    Text(
                        stringResource(R.string.delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deletion.busy,
                    onClick = { drawer(DrawerAction.DismissDeletion) },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    deletion.result
        ?.takeIf { deletion.resultDialog }
        ?.let { result ->
            AlertDialog(
                onDismissRequest = { drawer(DrawerAction.DismissDeletion) },
                title = { Text(stringResource(R.string.drawer_delete_partial)) },
                text = {
                    Text(
                        stringResource(
                            R.string.drawer_delete_partial_details,
                            result.deleted.size,
                            result.failures.size + result.cleanupFailures.size,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { drawer(DrawerAction.DismissDeletion) }) {
                        Text(stringResource(R.string.confirm))
                    }
                },
            )
        }
}

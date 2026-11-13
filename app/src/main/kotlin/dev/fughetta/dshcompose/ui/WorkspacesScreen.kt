package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.adaptiveColumnCount
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacesScreen(
    state: WorkspacesUiState,
    onAction: (AppAction) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val sections =
        remember(state.rows, state.preferences) { workspaceSections(state.rows, state.preferences) }
    val positions =
        remember(state.rows) { state.rows.withIndex().associate { it.value.id to it.index } }
    val editable = !state.loading && !state.writing && state.error == null
    val wide = windowTier().atLeastExpanded
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspaces)) },
                navigationIcon = { UpButton() },
                actions = {
                    ActionIcon(
                        R.drawable.ic_refresh,
                        stringResource(R.string.refresh),
                        enabled = state.connected && !state.loading && !state.writing,
                    ) {
                        onAction(AppAction.ReloadWorkspaces)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAction(AppAction.AddWorkspace) },
                icon = { Icon(painterResource(R.drawable.ic_add), null) },
                text = { Text(stringResource(R.string.workspace_add)) },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Expanded windows get a card grid; phones keep the single-column list.
            val columns = if (wide) adaptiveColumnCount(minItemWidth = 320.dp) else 1
            CenteredContent(
                Modifier.fillMaxSize(),
                maxContentWidth = if (wide) ContentWidths.Board else ContentWidths.Reading,
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp, Spacing.Large, 0.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                ) {
                    if (!state.connected)
                        item { Text(stringResource(R.string.engine_not_connected)) }
                    if (state.loading || state.writing)
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.preferencesError?.let { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error) }
                    }
                    state.error?.let { error ->
                        item { Text(error, color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        FilledTonalButton(
                            onClick = { onSelect(null) },
                            enabled = state.connected && !state.loading && state.preferencesLoaded,
                        ) {
                            Text(stringResource(R.string.workspace_all))
                        }
                    }
                    for (section in sections) {
                        item(key = "section:${section.kind}:${section.name}") {
                            Text(
                                section.name
                                    ?: stringResource(
                                        if (section.kind == "pinned")
                                            R.string.workspace_pinned_section
                                        else R.string.workspace_other_section,
                                    ),
                                Modifier.padding(
                                    start = Spacing.Large,
                                    top = Spacing.Large,
                                    bottom = Spacing.ExtraSmall,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (columns <= 1) {
                            items(
                                section.rows,
                                key = { it.id },
                                contentType = { "workspace" },
                            ) { workspace ->
                                WorkspaceCard(
                                    workspace = workspace,
                                    index = positions.getValue(workspace.id),
                                    pinned = workspace.id in state.preferences.pinned,
                                    state = state,
                                    editable = editable,
                                    wide = false,
                                    onAction = onAction,
                                    onSelect = onSelect,
                                )
                            }
                        } else {
                            section.rows.chunked(columns).forEachIndexed { rowIndex, row ->
                                item(
                                    key = "row:${section.kind}:${section.name}:$rowIndex",
                                    contentType = { "workspace-row" },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(Spacing.Medium),
                                    ) {
                                        row.forEach { workspace ->
                                            Box(Modifier.weight(1f)) {
                                                WorkspaceCard(
                                                    workspace = workspace,
                                                    index = positions.getValue(workspace.id),
                                                    pinned =
                                                        workspace.id in state.preferences.pinned,
                                                    state = state,
                                                    editable = editable,
                                                    wide = true,
                                                    onAction = onAction,
                                                    onSelect = onSelect,
                                                )
                                            }
                                        }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One workspace. Compact windows keep the two-line list item; wide windows show a card with the
 * path, the conversation count and the last update so a grid of them stays scannable.
 */
@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    index: Int,
    pinned: Boolean,
    state: WorkspacesUiState,
    editable: Boolean,
    wide: Boolean,
    onAction: (AppAction) -> Unit,
    onSelect: (String?) -> Unit,
) {
    val enabled = !state.loading && state.preferencesLoaded
    OutlinedCard(
        onClick = { onSelect(workspace.id) },
        Modifier.fillMaxWidth().heightIn(min = if (wide) 132.dp else 0.dp),
        enabled = enabled,
    ) {
        if (!wide)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                supportingContent = {
                    Column {
                        Text(
                            workspace.path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.workspace_session_count,
                                workspace.sessionIds.size,
                            ),
                        )
                    }
                },
                trailingContent = {
                    WorkspaceActionsMenu(workspace, index, pinned, state, editable, onAction)
                },
            ) {
                Text(
                    workspace.title.ifBlank { workspace.path },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        else
            Column(
                Modifier.fillMaxWidth().padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        workspace.title.ifBlank { workspace.path },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    WorkspaceActionsMenu(workspace, index, pinned, state, editable, onAction)
                }
                Text(
                    workspace.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                ) {
                    Text(
                        stringResource(
                            R.string.workspace_session_count,
                            workspace.sessionIds.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pinned)
                        Text(
                            stringResource(R.string.workspace_pinned_section),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                }
                workspace.updatedAt?.takeIf(String::isNotBlank)?.let { updated ->
                    Text(
                        stringResource(
                            R.string.workspace_updated_at,
                            workspaceUpdatedLabel(updated),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
    }
}

/** Rename / group / pin / reorder / remove, shared by the list row and the grid card. */
@Composable
private fun WorkspaceActionsMenu(
    workspace: Workspace,
    index: Int,
    pinned: Boolean,
    state: WorkspacesUiState,
    editable: Boolean,
    onAction: (AppAction) -> Unit,
) {
    OverflowMenu { close ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_rename)) },
            enabled = editable,
            onClick = {
                close()
                onAction(AppAction.ManageWorkspace(workspace.id, WorkspaceOperation.Rename))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_group)) },
            enabled = state.preferencesLoaded && editable,
            onClick = {
                close()
                onAction(AppAction.ManageWorkspace(workspace.id, WorkspaceOperation.Group))
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (pinned) R.string.workspace_unpin_local
                        else R.string.workspace_pin_local,
                    ),
                )
            },
            enabled = state.preferencesLoaded,
            onClick = {
                close()
                onAction(AppAction.PinWorkspaceLocally(workspace.id, !pinned))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_move_up)) },
            enabled = index > 0 && editable,
            onClick = {
                close()
                onAction(AppAction.MoveWorkspace(workspace.id, state.rows[index - 1].id))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_move_down)) },
            enabled = index < state.rows.lastIndex && editable,
            onClick = {
                close()
                onAction(AppAction.MoveWorkspace(workspace.id, state.rows.getOrNull(index + 2)?.id))
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.remove),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            enabled = editable,
            onClick = {
                close()
                onAction(AppAction.ManageWorkspace(workspace.id, WorkspaceOperation.Remove))
            },
        )
    }
}

/** `updatedAt` arrives as an ISO instant from the host; show it in the local short form. */
@Composable
private fun workspaceUpdatedLabel(raw: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(raw, locale) {
        runCatching {
            java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT,
                    locale,
                )
                .format(java.util.Date.from(java.time.Instant.parse(raw)))
        }
            .getOrElse { raw }
    }
}

@Composable
internal fun WorkspaceSessionDialog(
    state: WorkspaceSessionDialogState,
    onAction: (AppAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseWorkspaceSessionDialog) },
        title = { Text(stringResource(workspaceSessionLabel(state.operation))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                Text(
                    stringResource(
                        when (state.operation) {
                            WorkspaceSessionOperation.Pin,
                            WorkspaceSessionOperation.Unpin -> R.string.workspace_pin_details
                            WorkspaceSessionOperation.Archive -> R.string.workspace_archive_details
                            WorkspaceSessionOperation.Restore ->
                                if (state.openAfterRestore) R.string.session_search_restore_details
                                else R.string.workspace_restore_details
                        },
                    ),
                )
                if (state.operation == WorkspaceSessionOperation.Archive)
                    ListItem(
                        modifier =
                            Modifier.toggleable(
                                value = state.stopActivity,
                                enabled = !state.busy,
                                role = Role.Checkbox,
                                onValueChange = { onAction(AppAction.StopActivityOnArchive(it)) },
                            ),
                        leadingContent = {
                            Checkbox(
                                checked = state.stopActivity,
                                onCheckedChange = null,
                                enabled = !state.busy,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.workspace_stop_activity))
                    }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(AppAction.ConfirmWorkspaceSession) },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(AppAction.CloseWorkspaceSessionDialog) },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

internal fun workspaceSessionLabel(operation: WorkspaceSessionOperation) =
    when (operation) {
        WorkspaceSessionOperation.Pin -> R.string.workspace_pin
        WorkspaceSessionOperation.Unpin -> R.string.workspace_unpin
        WorkspaceSessionOperation.Archive -> R.string.workspace_archive
        WorkspaceSessionOperation.Restore -> R.string.workspace_restore
    }

@Composable
internal fun WorkspaceDialog(state: WorkspaceDialogState, onAction: (AppAction) -> Unit) {
    val remove = state.operation == WorkspaceOperation.Remove
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseWorkspaceDialog) },
        title = {
            Text(
                stringResource(
                    when (state.operation) {
                        WorkspaceOperation.Remove -> R.string.workspace_remove
                        WorkspaceOperation.Rename -> R.string.workspace_rename
                        WorkspaceOperation.Group -> R.string.workspace_group
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                if (remove) Text(stringResource(R.string.workspace_remove_details))
                else
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { onAction(AppAction.EditWorkspaceTitle(it)) },
                        enabled = !state.busy,
                        singleLine = true,
                        label = {
                            Text(
                                stringResource(
                                    if (state.operation == WorkspaceOperation.Group)
                                        R.string.workspace_group_name
                                    else R.string.workspace_title,
                                ),
                            )
                        },
                    )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(AppAction.ConfirmWorkspaceOperation) },
                enabled =
                    !state.busy &&
                        (state.operation != WorkspaceOperation.Rename || state.title.isNotBlank()),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(AppAction.CloseWorkspaceDialog) },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

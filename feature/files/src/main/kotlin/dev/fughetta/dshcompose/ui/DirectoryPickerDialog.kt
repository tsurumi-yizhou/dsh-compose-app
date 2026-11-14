package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.files.R

@Composable
fun DirectoryPickerDialog(state: DirectoryPickerState, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseDirectoryPicker) },
        title = {
            Text(
                stringResource(
                    if (state.registerWorkspace) R.string.workspace_add
                    else R.string.directory_new_conversation,
                ),
            )
        },
        text = {
            Column(
                Modifier.heightIn(min = 280.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                OutlinedTextField(
                    value = state.path,
                    onValueChange = { onAction(AppAction.EditDirectoryPath(it)) },
                    enabled = !state.busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.directory_path)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    TextButton(
                        onClick = { onAction(AppAction.BrowseDirectory(state.path)) },
                        enabled = !state.busy && state.path.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.directory_go))
                    }
                    TextButton(
                        onClick = { onAction(AppAction.BrowseDirectory()) },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(R.string.directory_home))
                    }
                }
                if (state.recent.isNotEmpty())
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
                        items(state.recent, key = { it }) { path ->
                            SuggestionChip(
                                onClick = { onAction(AppAction.BrowseDirectory(path)) },
                                label = {
                                    Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                enabled = !state.busy,
                            )
                        }
                    }
                state.level?.let { level ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
                        items(level.crumbs, key = { it.path }) { crumb ->
                            TextButton(
                                onClick = { onAction(AppAction.BrowseDirectory(crumb.path)) },
                                enabled = !state.busy && !state.loading,
                            ) {
                                Text(crumb.name)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { onAction(AppAction.FilterDirectories(it)) },
                        singleLine = true,
                        enabled = !state.busy && !state.loading,
                        label = { Text(stringResource(R.string.directory_filter)) },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onAction(AppAction.OpenCreateDirectory) },
                            enabled = state.canChoose,
                        ) {
                            Text(stringResource(R.string.directory_create))
                        }
                        FilterChip(
                            selected = state.showHidden,
                            onClick = {
                                onAction(AppAction.ShowHiddenDirectories(!state.showHidden))
                            },
                            label = { Text(stringResource(R.string.directory_hidden)) },
                            enabled = !state.busy && !state.loading,
                        )
                    }
                    if (level.truncated)
                        Text(
                            stringResource(R.string.directory_truncated),
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f)) {
                    items(state.entries, key = { it.path }, contentType = { "directory" }) { entry
                        ->
                        ListItem(
                            onClick = { onAction(AppAction.BrowseDirectory(entry.path)) },
                            enabled = !state.busy && !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(AppAction.ChooseDirectory) },
                enabled = state.canChoose,
            ) {
                Text(
                    stringResource(
                        if (state.registerWorkspace) R.string.workspace_add
                        else CoreDesignsystemR.string.new_conversation,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(AppAction.CloseDirectoryPicker) },
                enabled = !state.busy,
            ) {
                Text(stringResource(CoreDesignsystemR.string.cancel))
            }
        },
    )
    state.newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CloseCreateDirectory) },
            title = { Text(stringResource(R.string.directory_create)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { onAction(AppAction.EditNewDirectory(it)) },
                        singleLine = true,
                        enabled = !state.busy,
                        label = { Text(stringResource(R.string.directory_name)) },
                    )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(AppAction.ConfirmCreateDirectory) },
                    enabled = !state.busy && state.canCreateFolder,
                ) {
                    Text(stringResource(CoreDesignsystemR.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(AppAction.CloseCreateDirectory) },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(CoreDesignsystemR.string.cancel))
                }
            },
        )
    }
}

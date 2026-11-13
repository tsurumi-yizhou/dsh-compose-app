package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.images.WorkspaceImage

@Composable
internal fun WorkspaceFilesDialog(state: WorkspaceFilesUiState, onAction: (AppAction) -> Unit) {
    LifecycleStartEffect(state.sessionId) {
        onAction(AppAction.ObserveWorkspaceFiles(true))
        onStopOrDispose { onAction(AppAction.ObserveWorkspaceFiles(false)) }
    }
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseWorkspaceFiles) },
        title = { Text(stringResource(R.string.workspace_files)) },
        text = {
            Column(
                Modifier.heightIn(min = 240.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.search.query,
                    onValueChange = { onAction(AppAction.SearchWorkspaceFiles(it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.workspace_files_search)) },
                    trailingIcon = {
                        if (state.search.query.isNotEmpty())
                            TextButton(onClick = { onAction(AppAction.SearchWorkspaceFiles("")) }) {
                                Text(stringResource(R.string.workspace_files_clear_search))
                            }
                    },
                )
                Text(state.path.ifEmpty { "." }, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(
                        onClick = { onAction(AppAction.BrowseWorkspaceFiles("")) },
                        enabled = !state.loading,
                    ) {
                        Text(stringResource(R.string.workspace_files_root))
                    }
                    TextButton(
                        onClick = {
                            onAction(
                                AppAction.BrowseWorkspaceFiles(
                                    state.path.substringBeforeLast('/', ""),
                                ),
                            )
                        },
                        enabled = !state.loading && state.path.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.workspace_files_parent))
                    }
                    TextButton(
                        onClick = { onAction(AppAction.RefreshWorkspaceFiles) },
                        enabled = !state.loading && !state.search.loading,
                    ) {
                        Text(stringResource(R.string.refresh))
                    }
                }
                if (state.loading || state.search.loading)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                state.search.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.watchError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.listing?.truncated == true)
                    Text(stringResource(R.string.workspace_files_truncated))
                LazyColumn(Modifier.weight(1f)) {
                    if (state.search.query.isNotBlank()) {
                        if (
                            !state.search.loading &&
                                state.search.error == null &&
                                state.search.results.isEmpty()
                        )
                            item { Text(stringResource(R.string.workspace_files_no_results)) }
                        items(
                            state.search.results,
                            key = { it.path },
                            contentType = { "search-result" },
                        ) { result ->
                            TextButton(
                                onClick = {
                                    if (result.isDirectory)
                                        onAction(AppAction.BrowseWorkspaceFiles(result.path))
                                    else onAction(AppAction.PreviewWorkspaceFile(result.path))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (result.isDirectory) "${result.path}/" else result.path)
                            }
                        }
                    } else {
                        items(
                            state.listing?.entries.orEmpty(),
                            key = { it.name },
                            contentType = { it.type },
                        ) { entry ->
                            val path =
                                if (state.path.isEmpty()) entry.name
                                else "${state.path.trimEnd('/')}/${entry.name}"
                            TextButton(
                                onClick = {
                                    if (entry.type == "directory")
                                        onAction(AppAction.BrowseWorkspaceFiles(path))
                                    else onAction(AppAction.PreviewWorkspaceFile(path))
                                },
                                enabled = entry.type == "directory" || entry.type == "file",
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (entry.type == "directory") "${entry.name}/" else entry.name,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AppAction.CloseWorkspaceFiles) }) {
                Text(stringResource(R.string.confirm))
            }
        },
    )
    state.preview?.let { preview ->
        val listState = rememberLazyListState()
        LaunchedEffect(preview.page) { listState.scrollToItem(0) }
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CloseWorkspaceText) },
            title = { Text(preview.path) },
            text = {
                Column(
                    Modifier.heightIn(min = 240.dp, max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (preview.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    preview.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    preview.watchError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (preview.missingFonts.isNotEmpty())
                        Text(
                            stringResource(
                                R.string.workspace_office_missing_fonts,
                                preview.missingFonts.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    TextButton(
                        onClick = {
                            onAction(AppAction.ExportWorkspaceFile(state.sessionId, preview.path))
                        },
                    ) {
                        Text(stringResource(R.string.workspace_export))
                    }
                    if (preview.kind == WorkspacePreviewKind.Text)
                        Row {
                            TextButton(
                                onClick = { onAction(AppAction.PageWorkspaceText(false)) },
                                enabled =
                                    !preview.loading &&
                                        !preview.stale &&
                                        preview.previous.isNotEmpty(),
                            ) {
                                Text(stringResource(R.string.workspace_file_previous))
                            }
                            TextButton(
                                onClick = { onAction(AppAction.PageWorkspaceText(true)) },
                                enabled =
                                    !preview.loading &&
                                        !preview.stale &&
                                        preview.page?.let { !it.eof && it.count > 0 } == true,
                            ) {
                                Text(stringResource(R.string.workspace_file_next))
                            }
                            TextButton(
                                onClick = { onAction(AppAction.ReloadWorkspaceText) },
                                enabled = !preview.loading,
                            ) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    if (preview.kind != WorkspacePreviewKind.Text) {
                        preview.cachePath?.let { path ->
                            if (
                                preview.kind == WorkspacePreviewKind.Pdf ||
                                    preview.kind == WorkspacePreviewKind.Office
                            )
                                WorkspacePdf(
                                    path,
                                    preview.path,
                                    Modifier.fillMaxWidth().weight(1f),
                                    pagingEnabled = !preview.stale && !preview.loading,
                                )
                            else
                                WorkspaceImage(
                                    path,
                                    preview.path,
                                    Modifier.fillMaxWidth().weight(1f),
                                )
                        }
                        TextButton(
                            onClick = { onAction(AppAction.ReloadWorkspaceText) },
                            enabled = !preview.loading,
                        ) {
                            Text(stringResource(R.string.refresh))
                        }
                    } else
                        SelectionContainer(Modifier.weight(1f)) {
                            LazyColumn(state = listState) {
                                itemsIndexed(preview.page?.lines.orEmpty()) { index, line ->
                                    Text(
                                        "${(preview.page?.offset ?: 1) + index}  $line",
                                        fontFamily =
                                            dev.fughetta.dshcompose.ui.appearance
                                                .LocalAppearanceCodeFont
                                                .current,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(AppAction.CloseWorkspaceText) }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}

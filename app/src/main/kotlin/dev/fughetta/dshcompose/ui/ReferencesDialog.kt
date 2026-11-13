package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.references.formatFileMention

@Composable
internal fun ReferencesDialog(state: ReferencePickerState, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseReferences) },
        // The platform width leaves long paths a sliver; take the screen less a gutter.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.references_title)) },
        text = {
            Column(
                Modifier.heightIn(min = 360.dp, max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    for ((index, kind) in ReferenceKind.entries.withIndex()) SegmentedButton(
                        selected = state.kind == kind,
                        shape =
                            SegmentedButtonDefaults.itemShape(index, ReferenceKind.entries.size),
                        enabled = !state.pathOnly || kind == ReferenceKind.Files,
                        onClick = { onAction(AppAction.ReferenceType(kind)) },
                    ) {
                        Text(
                            stringResource(
                                if (kind == ReferenceKind.Files) R.string.references_files
                                else R.string.references_conversations,
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onAction(AppAction.ReferenceQuery(it)) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.references_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f)) {
                    if (state.kind == ReferenceKind.Files)
                        items(state.files, key = { it.path }, contentType = { "file-reference" }) {
                            candidate ->
                            ReferenceRow(
                                if (candidate.isDirectory) "${candidate.path}/" else candidate.path,
                                enabled =
                                    !state.loading &&
                                        formatFileMention(candidate.path, candidate.isDirectory) !=
                                            null,
                            ) {
                                onAction(AppAction.InsertFileReference(candidate.path))
                            }
                        }
                    else
                        items(
                            state.sessions,
                            key = { it.sessionId },
                            contentType = { "session-reference" },
                        ) { candidate ->
                            ReferenceRow(candidate.title, candidate.cwd, !state.loading) {
                                onAction(AppAction.InsertSessionReference(candidate.sessionId))
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AppAction.CloseReferences) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun InlineReferences(state: ReferencePickerState, onAction: (AppAction) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.heightIn(max = 240.dp).fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (kind in ReferenceKind.entries) if (
                    (state.sessionId != dev.fughetta.dshcompose.ui.DRAFT_SESSION_ID ||
                        kind == ReferenceKind.Conversations) &&
                        (!state.pathOnly || kind == ReferenceKind.Files)
                )
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { onAction(AppAction.ReferenceType(kind)) },
                        label = {
                            Text(
                                stringResource(
                                    if (kind == ReferenceKind.Files) R.string.references_files
                                    else R.string.references_conversations,
                                ),
                            )
                        },
                    )
                TextButton(onClick = { onAction(AppAction.CloseReferences) }) {
                    Text(stringResource(R.string.close))
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (
                !state.loading &&
                    state.error == null &&
                    state.files.isEmpty() &&
                    state.sessions.isEmpty()
            ) {
                Text(
                    stringResource(R.string.composer_no_mentions),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn {
                if (state.kind == ReferenceKind.Files)
                    items(state.files, key = { it.path }, contentType = { "file-reference" }) {
                        candidate ->
                        ReferenceRow(
                            if (candidate.isDirectory) "${candidate.path}/" else candidate.path,
                            enabled =
                                !state.loading &&
                                    formatFileMention(
                                        candidate.path,
                                        candidate.isDirectory,
                                        state.quoted,
                                    ) != null,
                        ) {
                            onAction(AppAction.InsertFileReference(candidate.path))
                        }
                    }
                else
                    items(
                        state.sessions,
                        key = { it.sessionId },
                        contentType = { "session-reference" },
                    ) { candidate ->
                        ReferenceRow(candidate.title, enabled = !state.loading) {
                            onAction(AppAction.InsertSessionReference(candidate.sessionId))
                        }
                    }
            }
        }
    }
}

/** A candidate across the whole width, so the row (not just its text) is the target. */
@Composable
private fun ReferenceRow(
    title: String,
    detail: String? = null,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        content = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) } },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors =
            ListItemDefaults.colors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
    )
}

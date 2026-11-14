package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifact
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifactKind
import dev.fughetta.dshcompose.domain.sessions.TranscriptFileChange
import dev.fughetta.dshcompose.domain.transcript.TranscriptTurnIndex
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont
import kotlinx.coroutines.*

@Composable
fun TranscriptArtifactsCard(
    files: List<TranscriptArtifact>,
    onAction: (TranscriptArtifact, TranscriptArtifactAction) -> Unit,
    modifier: Modifier = Modifier,
    hiddenPaths: Set<String> = emptySet(),
) {
    val shown = remember(files, hiddenPaths) { files.filter { it.path !in hiddenPaths } }
    if (shown.isEmpty()) return
    var expanded by rememberSaveable(files.firstOrNull()?.turnId) { mutableStateOf(false) }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(CoreDesignsystemR.string.transcript_artifacts),
                style = MaterialTheme.typography.titleMedium,
            )
            if (expanded)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(shown.size, key = { shown[it].path }) {
                        TranscriptArtifactTile(shown[it], onAction)
                    }
                }
            else
                shown.take(4).forEach { file ->
                    key(file.path) { TranscriptArtifactTile(file, onAction) }
                }
            if (shown.size > 4)
                TextButton({ expanded = !expanded }) {
                    Text(
                        if (expanded) stringResource(R.string.transcript_close)
                        else "${stringResource(R.string.transcript_more)} · ${shown.size-4}",
                    )
                }
        }
    }
}

@Composable
private fun TranscriptArtifactTile(
    file: TranscriptArtifact,
    onAction: (TranscriptArtifact, TranscriptArtifactAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            TextButton(
                { onAction(file, TranscriptArtifactAction.Preview) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            file.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                stringResource(kindLabel(file.kind)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton({ menu = true }) {
                Icon(
                    painterResource(CoreDesignsystemR.drawable.ic_more),
                    stringResource(CoreDesignsystemR.string.more_actions),
                )
            }
            DropdownMenu(menu, { menu = false }) {
                TranscriptArtifactAction.entries.forEach { action ->
                    DropdownMenuItem(
                        { Text(stringResource(actionLabel(action))) },
                        {
                            menu = false
                            onAction(file, action)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptAllArtifactsSheet(
    index: TranscriptTurnIndex,
    onAction: (TranscriptArtifact, TranscriptArtifactAction) -> Unit,
    onDismiss: () -> Unit,
    hiddenPaths: Set<String> = emptySet(),
) {
    val files =
        remember(index.artifacts, hiddenPaths) {
            index.artifacts
                .filter { it.path !in hiddenPaths }
                .sortedByDescending { index.trace(it.turnId)?.endSeq ?: it.seq ?: 0 }
        }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    stringResource(CoreDesignsystemR.string.transcript_artifacts),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            items(files.size, key = { files[it].path }) {
                TranscriptArtifactTile(files[it], onAction)
            }
        }
    }
}

@Composable
fun TranscriptChangesCard(
    changes: List<TranscriptFileChange>,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (changes.isEmpty()) return
    var opened by remember { mutableStateOf<TranscriptFileChange?>(null) }
    var allChanges by remember { mutableStateOf(false) }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.transcript_changes),
                style = MaterialTheme.typography.titleMedium,
            )
            changes.take(6).forEach { file ->
                Row {
                    TextButton({ opened = file }, Modifier.weight(1f)) {
                        Text(
                            file.path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = LocalAppearanceCodeFont.current,
                        )
                    }
                    TextButton({ onOpenFile(file.path) }) {
                        Text(stringResource(R.string.transcript_open_file))
                    }
                }
            }
        }
    }
    if (changes.size > 6)
        TextButton({ allChanges = true }) { Text(stringResource(R.string.transcript_more)) }
    if (allChanges)
        Dialog({ allChanges = false }) {
            Surface {
                LazyColumn(Modifier.heightIn(max = 560.dp)) {
                    items(changes.size) { index ->
                        TextButton(
                            {
                                opened = changes[index]
                                allChanges = false
                            },
                        ) {
                            Text(changes[index].path)
                        }
                    }
                }
            }
        }
    opened?.let { file ->
        TranscriptChangesDialog(changes, changes.indexOf(file)) { opened = null }
    }
}

private fun kindLabel(kind: TranscriptArtifactKind): Int =
    when (kind) {
        TranscriptArtifactKind.Image -> R.string.transcript_image
        TranscriptArtifactKind.Pdf -> R.string.transcript_pdf
        TranscriptArtifactKind.Document -> R.string.transcript_document
        TranscriptArtifactKind.Spreadsheet -> R.string.transcript_sheet
        TranscriptArtifactKind.Slides -> R.string.transcript_slides
        TranscriptArtifactKind.Archive -> R.string.transcript_archive
        TranscriptArtifactKind.Audio -> R.string.transcript_audio
        TranscriptArtifactKind.Video -> R.string.transcript_video
        TranscriptArtifactKind.Web -> R.string.transcript_web_page
        else -> R.string.transcript_file
    }

private fun actionLabel(action: TranscriptArtifactAction): Int =
    when (action) {
        TranscriptArtifactAction.Preview -> R.string.transcript_preview
        TranscriptArtifactAction.Open -> R.string.transcript_open_file
        TranscriptArtifactAction.OpenWith -> R.string.transcript_open_with
        TranscriptArtifactAction.Share -> R.string.transcript_share
        TranscriptArtifactAction.Save -> R.string.transcript_save
        TranscriptArtifactAction.CopyPath -> R.string.transcript_copy_path
    }

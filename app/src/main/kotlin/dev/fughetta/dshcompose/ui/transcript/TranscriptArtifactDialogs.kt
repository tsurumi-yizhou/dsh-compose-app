package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont

@Composable
fun TranscriptArtifactStatus(state: TranscriptArtifactState, modifier: Modifier = Modifier) {
    if (state.busy)
        Column(modifier) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "${state.bytes}${state.total?.let {" / $it"}.orEmpty()} bytes",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    state.error?.let {
        Text(
            stringResource(
                if (it == "workspace-file/too-large" || it == "artifact/workbook-too-large")
                    R.string.transcript_file_large
                else if (it == "artifact/storage-denied") R.string.transcript_grant_storage
                else R.string.transcript_file_failed,
            ),
            modifier,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.notice != null)
        Text(
            stringResource(R.string.transcript_saved),
            modifier,
            style = MaterialTheme.typography.labelLarge,
        )
}

@Composable
fun TranscriptSheetDialog(
    view: TranscriptSheetPreview?,
    onSelect: (Int) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    view ?: return
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    Text(
                        view.path.substringAfterLast('/'),
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onRefresh) { Text(stringResource(R.string.transcript_refresh)) }
                    TextButton(onDismiss) { Text(stringResource(R.string.transcript_close)) }
                }
                if (view.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (view.error != null)
                    Text(
                        stringResource(R.string.transcript_file_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                if (view.changed)
                    Text(
                        stringResource(R.string.transcript_file_changed),
                        style = MaterialTheme.typography.labelMedium,
                    )
                if (view.sheets.size > 1)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(view.sheets.size) { index ->
                            FilterChip(
                                view.selected == index,
                                { onSelect(index) },
                                label = { Text(view.sheets[index].name) },
                            )
                        }
                    }
                view.sheets.getOrNull(view.selected)?.let { sheet ->
                    if (sheet.truncated)
                        Text(
                            stringResource(R.string.transcript_preview_truncated),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    SheetGrid(sheet, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/**
 * One horizontal viewport controls every visible row; offscreen cells are spacers, never Text
 * nodes.
 */
@Composable
private fun SheetGrid(sheet: TranscriptSheet, modifier: Modifier) {
    var selected by remember(sheet) { mutableStateOf<Pair<Int, Int>?>(null) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val cellWidth = 152.dp
    val cellPixels = with(density) { cellWidth.toPx() }
    BoxWithConstraints(modifier) {
        val paneWidth = maxWidth
        val columns = sheet.columns.coerceAtMost(200)
        val start = (scroll.value / cellPixels).toInt().coerceIn(0, columns.coerceAtLeast(1) - 1)
        val visible = (maxWidth.value / cellWidth.value).toInt() + 2
        val end = (start + visible).coerceAtMost(columns)
        val width = cellWidth * columns
        Box(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            LazyColumn(Modifier.width(maxOf(width, paneWidth)).fillMaxHeight()) {
                item("headers") {
                    Row(Modifier.background(MaterialTheme.colorScheme.secondaryContainer)) {
                        Spacer(Modifier.width(cellWidth * start))
                        for (column in start until end) Text(
                            columnName(column),
                            Modifier.width(cellWidth).padding(8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.width(cellWidth * (columns - end)))
                    }
                }
                items(sheet.rows.size, key = { it }) { row ->
                    val cells = sheet.rows[row]
                    Row(
                        Modifier.heightIn(min = 40.dp)
                            .background(
                                if (row % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow
                                else MaterialTheme.colorScheme.surface,
                            ),
                    ) {
                        Spacer(Modifier.width(cellWidth * start))
                        for (column in start until end) SelectionContainer {
                            Text(
                                cells.getOrNull(column).orEmpty(),
                                Modifier.width(cellWidth)
                                    .clickable { selected = row to column }
                                    .padding(8.dp),
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = LocalAppearanceCodeFont.current,
                            )
                        }
                        Spacer(Modifier.width(cellWidth * (columns - end)))
                    }
                }
            }
        }
    }
    selected?.let { (row, column) ->
        val text = sheet.rows[row].getOrNull(column).orEmpty()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${columnName(column)}${row+1}") },
            text = {
                dev.fughetta.dshcompose.ui.markdown.CodeRows(
                    dev.fughetta.dshcompose.ui.markdown.MarkdownBlock.Code(text, ""),
                )
            },
            confirmButton = { TranscriptCopyButton(text) },
            dismissButton = {
                TextButton({ selected = null }) { Text(stringResource(R.string.transcript_close)) }
            },
        )
    }
}

private fun columnName(index: Int): String {
    var n = index + 1
    val result = StringBuilder()
    while (n > 0) {
        n--
        result.append(('A'.code + n % 26).toChar())
        n /= 26
    }
    return result.reverse().toString()
}

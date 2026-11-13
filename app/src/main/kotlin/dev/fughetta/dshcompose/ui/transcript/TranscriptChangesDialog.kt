package dev.fughetta.dshcompose.ui.transcript

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.TranscriptFileChange
import dev.fughetta.dshcompose.domain.transcript.*
import dev.fughetta.dshcompose.domain.transcript.TranscriptDiffKind
import dev.fughetta.dshcompose.domain.transcript.TranscriptDiffLine
import dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings
import kotlinx.coroutines.*

private var transcriptWrapPreference = true

@Composable
fun TranscriptChangesDialog(
    files: List<TranscriptFileChange>,
    initial: Int = 0,
    onDismiss: () -> Unit,
) {
    if (files.isEmpty()) return
    var wrap by remember { mutableStateOf(transcriptWrapPreference) }
    val pager = rememberPagerState(initialPage = initial.coerceIn(files.indices)) { files.size }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val animations = LocalPerformanceSettings.current.animations
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Row {
                    Text(
                        stringResource(R.string.transcript_diff),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    FilterChip(
                        wrap,
                        {
                            wrap = !wrap
                            transcriptWrapPreference = wrap
                        },
                        label = { Text(stringResource(R.string.transcript_wrap)) },
                    )
                    TextButton(
                        {
                            scope.launch {
                                val file = files[pager.currentPage]
                                val patch =
                                    withContext(Dispatchers.Default) {
                                        transcriptPatch(file.path, file.blocks)
                                    }
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", patch)))
                            }
                        },
                    ) {
                        Text(stringResource(R.string.transcript_copy))
                    }
                    TextButton(onDismiss) { Text(stringResource(R.string.transcript_close)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(files.size) { index ->
                        FilterChip(
                            pager.currentPage == index,
                            {
                                scope.launch {
                                    if (animations) pager.animateScrollToPage(index)
                                    else pager.scrollToPage(index)
                                }
                            },
                            label = { Text(files[index].path.substringAfterLast('/')) },
                        )
                    }
                }
                HorizontalPager(
                    pager,
                    Modifier.weight(1f).fillMaxWidth(),
                    userScrollEnabled = wrap,
                ) { page ->
                    val file = files[page]
                    var lines by remember(file) { mutableStateOf<List<TranscriptDiffLine>?>(null) }
                    LaunchedEffect(file) {
                        lines =
                            withContext(Dispatchers.Default) {
                                file.blocks.flatMapIndexed { index, block ->
                                    (if (index > 0)
                                        listOf(
                                            TranscriptDiffLine(
                                                TranscriptDiffKind.Hunk,
                                                "@@ ${index+1} @@",
                                            ),
                                        )
                                    else emptyList()) +
                                        numberedTranscriptDiff(
                                            transcriptLineDiff(block.old.orEmpty(), block.new),
                                            block.oldStart,
                                            block.newStart,
                                        )
                                }
                            }
                    }
                    lines?.let { TranscriptDiffLines(it, Modifier.fillMaxSize(), wrap) }
                        ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

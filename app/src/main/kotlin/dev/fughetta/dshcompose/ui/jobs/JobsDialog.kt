package dev.fughetta.dshcompose.ui.jobs

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.jobs.JobInfo
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun jobStatus(job: JobInfo): String =
    if (job.status !in setOf("running", "stopping", "completed", "killed", "failed")) job.status
    else
        stringResource(
            when (job.status) {
                "running" -> R.string.jobs_running
                "stopping" -> R.string.jobs_stopping
                "completed" -> R.string.jobs_completed
                "killed" -> R.string.jobs_killed
                "failed" -> R.string.jobs_failed
                else -> error("Unknown job status")
            },
        )

@Composable
private fun jobSummary(job: JobInfo): String {
    val elapsed =
        remember(job) {
            job.startedAt?.let { start ->
                android.text.format.DateUtils.formatElapsedTime(
                    ((job.finishedAt ?: System.currentTimeMillis()) - start).coerceAtLeast(0) /
                        1000,
                )
            }
        }
    return listOfNotNull(
            jobStatus(job),
            elapsed?.let {
                stringResource(if (job.live) R.string.jobs_elapsed else R.string.jobs_duration, it)
            },
            job.progress?.takeIf { it.isNotBlank() },
        )
        .joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobsDialog(state: JobsUiState, onAction: (AppAction) -> Unit) {
    LifecycleStartEffect(state.sessionId) {
        onAction(AppAction.ObserveJobs(true))
        onStopOrDispose { onAction(AppAction.ObserveJobs(false)) }
    }
    val output = state.output
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyable =
        remember(output?.lines) {
            output != null && output.lines.sumOf { it.length.toLong() + 1 } <= 100_000
        }
    val listState = rememberLazyListState()
    var followTail by remember(output?.job?.id) { mutableStateOf(true) }
    LaunchedEffect(listState, output?.job?.id) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) followTail = !listState.canScrollForward }
    }
    LaunchedEffect(output?.lines) {
        if (output != null && followTail && output.lines.isNotEmpty())
            listState.scrollToItem(output.lines.lastIndex)
    }
    Dialog(
        onDismissRequest = {
            onAction(if (output == null) AppAction.CloseJobs else AppAction.CloseJobOutput)
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.jobs_title)) },
                    actions = {
                        TextButton(onClick = { onAction(AppAction.RefreshJobs) }) {
                            Text(stringResource(R.string.refresh))
                        }
                        TextButton(
                            onClick = {
                                onAction(
                                    if (output == null) AppAction.CloseJobs
                                    else AppAction.CloseJobOutput,
                                )
                            },
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading || output?.loading == true)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.export.shareFailed)
                    Text(
                        stringResource(R.string.jobs_share_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                if (state.export.saveFailed)
                    Text(
                        stringResource(R.string.jobs_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                state.error?.let { Text(jobsError(it), color = MaterialTheme.colorScheme.error) }
                if (output == null) {
                    if (!state.loading && state.jobs.isEmpty())
                        Text(stringResource(R.string.jobs_empty))
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.jobs, key = { it.id }, contentType = { "job" }) { job ->
                            OutlinedCard(
                                onClick = { onAction(AppAction.OpenJobOutput(job.id)) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                ListItem(
                                    supportingContent = {
                                        Column {
                                            Text(jobSummary(job))
                                            job.detail?.let {
                                                Text(
                                                    it,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        job.label,
                                        fontFamily =
                                            dev.fughetta.dshcompose.ui.appearance
                                                .LocalAppearanceCodeFont
                                                .current,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onAction(AppAction.ShareJobOutput) },
                            enabled =
                                output.lines.isNotEmpty() &&
                                    !state.export.sharing &&
                                    !state.export.saving,
                        ) {
                            Text(stringResource(R.string.jobs_share_output))
                        }

                        TextButton(
                            onClick = {
                                val lines = output.lines
                                scope.launch {
                                    val text =
                                        withContext(Dispatchers.Default) {
                                            lines.joinToString("\n")
                                        }
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("", text)),
                                    )
                                }
                            },
                            enabled = output.lines.isNotEmpty() && copyable,
                        ) {
                            Text(stringResource(R.string.jobs_copy_output))
                        }
                        TextButton(
                            onClick = { onAction(AppAction.SaveJobOutput) },
                            enabled =
                                output.lines.isNotEmpty() &&
                                    !state.export.saving &&
                                    !state.export.sharing,
                        ) {
                            Text(stringResource(R.string.jobs_save_output))
                        }
                    }
                    Text(
                        output.job.label,
                        fontFamily =
                            dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(jobSummary(output.job))
                    output.error?.let {
                        Text(jobsError(it), color = MaterialTheme.colorScheme.error)
                    }
                    output.notice?.let { Text(stringResource(it)) }
                    if (!copyable) Text(stringResource(R.string.jobs_copy_large))
                    if (output.trimmed) Text(stringResource(R.string.jobs_trimmed))
                    if (state.canStop && output.job.live)
                        TextButton(
                            onClick = { onAction(AppAction.RequestJobStop) },
                            enabled = !output.loading && !output.stopping && !output.stopRequested,
                        ) {
                            Text(
                                stringResource(
                                    if (output.stopping || output.stopRequested)
                                        R.string.jobs_stopping
                                    else R.string.stop,
                                ),
                            )
                        }
                    if (!output.loading && output.lines.isEmpty())
                        Text(
                            stringResource(
                                if (output.job.live) R.string.jobs_waiting
                                else R.string.jobs_output_empty,
                            ),
                        )
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(
                                output.lines.size,
                                key = { output.firstLine + it },
                                contentType = { "job-output" },
                            ) { index ->
                                Text(
                                    output.lines[index].ifEmpty { " " },
                                    fontFamily =
                                        dev.fughetta.dshcompose.ui.appearance
                                            .LocalAppearanceCodeFont
                                            .current,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (!followTail)
                        TextButton(
                            onClick = {
                                followTail = true
                                scope.launch {
                                    if (output.lines.isNotEmpty())
                                        listState.scrollToItem(output.lines.lastIndex)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.jobs_latest))
                        }
                }
            }
        }
    }
    if (output?.confirmStop == true)
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CancelJobStop) },
            title = { Text(stringResource(R.string.jobs_stop_title)) },
            text = { Text(output.job.label) },
            confirmButton = {
                TextButton(onClick = { onAction(AppAction.ConfirmJobStop) }) {
                    Text(stringResource(R.string.stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AppAction.CancelJobStop) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}

@Composable
private fun jobsError(code: String): String =
    when (code) {
        "jobs/not-connected" -> stringResource(R.string.engine_not_connected)
        "jobs/read-failed" -> stringResource(R.string.jobs_read_failed)
        else -> code
    }

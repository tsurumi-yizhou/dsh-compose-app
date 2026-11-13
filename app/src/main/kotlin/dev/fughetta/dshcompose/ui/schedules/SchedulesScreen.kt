package dev.fughetta.dshcompose.ui.schedules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.schedules.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import java.time.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SchedulesScreen(
    state: SchedulesUiState,
    onAction: (SchedulesAction) -> Unit,
    onBack: () -> Unit,
) {
    LifecycleStartEffect(Unit) {
        onAction(SchedulesAction.Observe(true))
        onStopOrDispose { onAction(SchedulesAction.Observe(false)) }
    }
    val detail = state.detail
    BackHandler(detail != null) { onAction(SchedulesAction.CloseTask) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    UpButton {
                        if (detail != null) onAction(SchedulesAction.CloseTask) else onBack()
                    }
                },
                actions = {
                    ActionIcon(
                        R.drawable.ic_refresh,
                        stringResource(R.string.schedules_refresh),
                        enabled = state.connected && !state.busy,
                    ) {
                        onAction(SchedulesAction.Refresh)
                    }
                    // The up button carries its own inset; match it so the refresh glyph
                    // is not the only thing hugging the window edge.
                    Spacer(Modifier.width(Spacing.Small))
                },
            )
        },
        floatingActionButton = {
            // The empty state already offers the create button; the FAB is for acting on
            // an existing list, so an engine without tasks shows one entry point only.
            if (
                detail == null &&
                    state.connected &&
                    state.canManage &&
                    !state.busy &&
                    state.total > 0
            )
                ExtendedFloatingActionButton(
                    onClick = { onAction(SchedulesAction.Create) },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = { Text(stringResource(R.string.schedules_create)) },
                )
        },
    ) { padding ->
        val wide = windowTier().atLeastExpanded
        SchedulesPane(Modifier.fillMaxSize().padding(padding), wide) { modifier, listPadding ->
            LazyColumn(
                modifier,
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                if (!state.connected) item { Text(stringResource(R.string.schedules_disconnected)) }
                if (state.connected && !state.canManage)
                    item { Text(stringResource(R.string.schedules_read_only)) }
                if (state.loading || state.busy || state.filtering)
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let {
                    item { Text(scheduleError(it), color = MaterialTheme.colorScheme.error) }
                }
                state.notice?.let { item { Text(stringResource(R.string.schedules_saved)) } }
                if (detail == null) {
                    val filtered = state.query.isNotBlank() || state.filter != SchedulesFilter.All
                    // Nothing to search or filter before the first task exists: keep the
                    // create guidance as the only thing on the page instead of a dead row.
                    if (filtered || state.total > 0) {
                        item {
                            OutlinedTextField(
                                state.query,
                                { onAction(SchedulesAction.Query(it)) },
                                label = { Text(stringResource(R.string.schedules_search)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                                SchedulesFilter.entries.forEach { filter ->
                                    FilterChip(
                                        selected = state.filter == filter,
                                        onClick = { onAction(SchedulesAction.Filter(filter)) },
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (filter) {
                                                        SchedulesFilter.All ->
                                                            R.string.schedules_all
                                                        SchedulesFilter.Active ->
                                                            R.string.schedules_active
                                                        SchedulesFilter.Other ->
                                                            R.string.schedules_other
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                stringResource(
                                    R.string.schedules_count,
                                    state.rows.size,
                                    state.total,
                                ),
                            )
                        }
                    }
                    if (state.rows.isEmpty() && !state.loading && !state.filtering)
                        item { SchedulesEmptyPanel(state, filtered, onAction) }
                    itemsIndexed(
                        state.rows,
                        key = { _, task -> task.id },
                        contentType = { _, _ -> "task" },
                    ) { index, task ->
                        Column {
                            SchedulesTaskRow(task, onAction)
                            if (index < state.rows.lastIndex)
                                SettingsRowDivider(startIndent = Spacing.Large)
                        }
                    }
                    if (state.hasMore)
                        item {
                            TextButton(
                                onClick = { onAction(SchedulesAction.LoadMore) },
                                enabled = !state.loadingMore,
                            ) {
                                Text(stringResource(R.string.schedules_more))
                            }
                        }
                    if (state.loadingMore) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                } else {
                    val task = detail.task
                    item {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                                Text(task.title, style = MaterialTheme.typography.headlineSmall)
                                Text(scheduleStatus(task.status))
                                Text(task.prompt)
                                Text(
                                    stringResource(
                                        R.string.schedules_next,
                                        scheduleInstant(task.scheduledAt),
                                    ),
                                )
                                Text(
                                    stringResource(
                                        R.string.schedules_last,
                                        scheduleInstant(task.lastDeliveredAt),
                                    ),
                                )
                                Text(scheduleRule(task))
                                Text(scheduleDestination(task))
                            }
                        }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                            TextButton(
                                onClick = { onAction(SchedulesAction.Edit) },
                                enabled = state.canManage && !state.busy,
                            ) {
                                Text(stringResource(R.string.schedules_edit))
                            }
                            TextButton(
                                onClick = { onAction(SchedulesAction.Run) },
                                enabled = state.canManage && !state.busy,
                            ) {
                                Text(stringResource(R.string.schedules_run))
                            }
                            TextButton(
                                onClick = { onAction(SchedulesAction.ToggleActive) },
                                enabled = state.canManage && !state.busy && task.status != "ended",
                            ) {
                                Text(
                                    stringResource(
                                        if (task.active) R.string.schedules_pause
                                        else R.string.schedules_resume,
                                    ),
                                )
                            }
                            TextButton(
                                onClick = { onAction(SchedulesAction.AskDelete) },
                                enabled = state.canManage && !state.busy,
                            ) {
                                Text(stringResource(R.string.schedules_delete))
                            }
                            // A new_once target can contain a reserved identity; only recorded
                            // deliveries prove it exists.
                            if (task.target.kind == ScheduleTargetKind.Session)
                                task.targetSessionId?.let { id ->
                                    TextButton(
                                        onClick = { onAction(SchedulesAction.OpenSession(id)) },
                                        enabled = !state.busy,
                                    ) {
                                        Text(stringResource(R.string.schedules_open_session))
                                    }
                                }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.schedules_history),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (detail.historyLoading)
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    detail.historyError?.let {
                        item {
                            Text(scheduleError(it), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onAction(SchedulesAction.RetryHistory) }) {
                                Text(stringResource(R.string.schedules_retry))
                            }
                        }
                    }
                    if (detail.historyLoaded && detail.runs.isEmpty())
                        item { Text(stringResource(R.string.schedules_history_empty)) }
                    itemsIndexed(
                        detail.runs,
                        key = { index, run -> run.messageId ?: "run-$index" },
                        contentType = { _, _ -> "run" },
                    ) { _, run ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.Medium)) {
                                Text(scheduleInstant(run.deliveredAt))
                                run.prompt?.let { Text(it) }
                                run.targetSessionId?.let { id ->
                                    TextButton(
                                        onClick = { onAction(SchedulesAction.OpenSession(id)) },
                                    ) {
                                        Text(stringResource(R.string.schedules_open_session))
                                    }
                                }
                            }
                        }
                    }
                    if (detail.nextBefore != null)
                        item {
                            TextButton(
                                onClick = { onAction(SchedulesAction.LoadHistory) },
                                enabled = !detail.historyLoading,
                            ) {
                                Text(stringResource(R.string.schedules_more))
                            }
                        }
                    if (detail.pruned || detail.unavailable)
                        item { Text(stringResource(R.string.schedules_history_pruned)) }
                    if (detail.retentionDays != null && detail.retentionRecords != null)
                        item {
                            Text(
                                stringResource(
                                    R.string.schedules_retention,
                                    detail.retentionDays,
                                    detail.retentionRecords,
                                ),
                            )
                        }
                }
            }
        }
    }
    state.editor?.let { SchedulesEditor(state, it, onAction) }
    state.deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) onAction(SchedulesAction.CancelDelete) },
            title = { Text(stringResource(R.string.schedules_delete)) },
            text = { Text(stringResource(R.string.schedules_delete_question, task.title)) },
            confirmButton = {
                TextButton(
                    onClick = { onAction(SchedulesAction.ConfirmDelete) },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.schedules_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(SchedulesAction.CancelDelete) },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.schedules_cancel))
                }
            },
        )
    }
}

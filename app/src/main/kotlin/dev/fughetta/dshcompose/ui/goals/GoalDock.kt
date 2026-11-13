package dev.fughetta.dshcompose.ui.goals

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun goalStatus(state: GoalUiState): String =
    stringResource(
        when {
            state.projection?.goal?.phase == GoalPhase.Blocked -> R.string.goal_blocked
            state.projection?.goal?.phase == GoalPhase.Paused -> R.string.goal_paused
            state.disarmed -> R.string.goal_disarmed
            state.activation == null -> R.string.goal_activation_unknown
            else -> R.string.goal_active
        },
    )

@Composable
internal fun GoalDock(state: GoalUiState, onAction: (AppAction) -> Unit) {
    val goal = state.projection?.goal ?: return
    if (!state.visible) return
    val headline =
        remember(goal.objective) {
            goal.objective
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
    Card(
        onClick = { onAction(AppAction.ShowGoal) },
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "${stringResource(R.string.goal_title)} · $headline",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                goalStatus(state),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (goal.phase == GoalPhase.Blocked) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun GoalDialog(state: GoalUiState, onAction: (AppAction) -> Unit) {
    val projection = state.projection
    val objective = projection?.goal?.objective.orEmpty()
    val lines by
        produceState<List<String>>(emptyList(), objective) {
            value = withContext(Dispatchers.Default) { objective.lines() }
        }
    if (state.dialog && projection != null)
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CloseGoal) },
            title = { Text(stringResource(R.string.goal_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                            items(lines.size) { index -> Text(lines[index]) }
                        }
                    }
                    Text(goalStatus(state), style = MaterialTheme.typography.titleSmall)
                    if (projection.goal.maxGoalRounds > 0)
                        Text(
                            stringResource(
                                R.string.goal_round_limit,
                                projection.roundsStarted,
                                projection.goal.maxGoalRounds,
                            ),
                        )
                    else Text(stringResource(R.string.goal_round, projection.roundsStarted))
                    projection.goal.blockedReason?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                    }
                    if (
                        state.activation == null &&
                            projection.goal.phase == GoalPhase.Active &&
                            !state.canRead
                    )
                        Text(stringResource(R.string.goal_remote_activation))
                    state.error?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.canRead && projection.goal.phase == GoalPhase.Active)
                        TextButton(
                            onClick = { onAction(AppAction.RefreshGoal) },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.refresh))
                        }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { onAction(AppAction.EditGoal) },
                            enabled = state.ready && !state.busy,
                        ) {
                            Text(stringResource(R.string.goal_edit))
                        }
                        if (state.canPause)
                            TextButton(
                                onClick = { onAction(AppAction.ChangeGoal(GoalAction.Pause)) },
                                enabled = state.ready && !state.busy,
                            ) {
                                Text(stringResource(R.string.goal_pause))
                            }
                        if (state.canResume)
                            TextButton(
                                onClick = { onAction(AppAction.ChangeGoal(GoalAction.Resume)) },
                                enabled = state.ready && !state.busy,
                            ) {
                                Text(stringResource(R.string.goal_resume))
                            }
                    }
                    TextButton(
                        onClick = { onAction(AppAction.AskClearGoal) },
                        enabled = state.ready && !state.busy,
                    ) {
                        Text(
                            stringResource(R.string.goal_clear),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(AppAction.CloseGoal) }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    state.editor?.let { editor ->
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CancelGoalEditor) },
            title = { Text(stringResource(R.string.goal_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        editor.text,
                        { onAction(AppAction.ChangeGoalObjective(it)) },
                        enabled = !state.busy,
                        minLines = 2,
                        maxLines = 6,
                        label = { Text(stringResource(R.string.goal_objective)) },
                    )
                    state.error?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(AppAction.SaveGoal) },
                    enabled =
                        state.ready &&
                            !state.busy &&
                            editor.text.trim().isNotEmpty() &&
                            editor.text.trim() != editor.initial.trim(),
                ) {
                    Text(stringResource(R.string.goal_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AppAction.CancelGoalEditor) }) {
                    Text(stringResource(if (state.busy) R.string.close else R.string.cancel))
                }
            },
        )
    }
    state.clearing?.let { clear ->
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CancelClearGoal) },
            title = { Text(stringResource(R.string.goal_clear_title)) },
            text = { Text(clear.objective, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = { onAction(AppAction.ConfirmClearGoal) },
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(R.string.goal_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AppAction.CancelClearGoal) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

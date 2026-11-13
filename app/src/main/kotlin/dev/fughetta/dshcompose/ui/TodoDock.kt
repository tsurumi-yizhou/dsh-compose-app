package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.sessions.TodoList
import dev.fughetta.dshcompose.ui.phone.PerformanceAnimatedVisibility

/** Read-only Host state; no per-row action can mark an engine task complete. */
@Composable
internal fun TodoDock(sessionId: String, summary: TodoList, running: Boolean) {
    if (summary.total == 0) return
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val current = summary.current
    val label =
        when {
            summary.allDone -> stringResource(R.string.todo_all_done)
            running && current?.status == "in_progress" ->
                stringResource(R.string.todo_current, current.content)
            else -> stringResource(R.string.todo_next, current?.content.orEmpty())
        }
    val expansion =
        stringResource(if (expanded) R.string.todo_expanded else R.string.todo_collapsed)
    val toggle = stringResource(if (expanded) R.string.todo_collapse else R.string.todo_expand)
    val window = LocalWindowInfo.current.containerSize
    val maxList = minOf(196.dp, with(LocalDensity.current) { window.height.toDp() * 0.3f })
    val list = rememberLazyListState()
    LaunchedEffect(expanded) {
        if (expanded && summary.currentIndex >= 0) list.scrollToItem(summary.currentIndex)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) { stateDescription = expansion }
                    .clickable(role = Role.Button, onClickLabel = toggle) { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.todo_summary, summary.done, summary.total, label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(toggle, style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(
                progress = { summary.done.toFloat() / summary.total },
                modifier = Modifier.fillMaxWidth(),
            )
            PerformanceAnimatedVisibility(expanded) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxList),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(summary.items.size, key = { it }, contentType = { "todo" }) { index ->
                        val todo = summary.items[index]
                        val completed = todo.status == "completed"
                        val active = todo.status == "in_progress"
                        val status =
                            stringResource(
                                when {
                                    completed -> R.string.todo_completed
                                    active -> R.string.todo_in_progress
                                    else -> R.string.todo_pending
                                },
                            )
                        val accessible =
                            stringResource(R.string.todo_item_description, todo.content, status)
                        Row(
                            Modifier.fillMaxWidth().clearAndSetSemantics {
                                contentDescription = accessible
                            },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (completed) "✓" else if (active) "●" else "○",
                                color =
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                todo.content,
                                Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color =
                                    if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

package dev.fughetta.dshcompose.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.ReadablePane
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AgentsRoute(source: StateFlow<AgentsState>, onAction: (AgentsAction) -> Unit) {
    val state by source.collectAsStateWithLifecycle()
    AgentsScreen(state, onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(state: AgentsState, onAction: (AgentsAction) -> Unit) {
    LifecycleStartEffect(Unit) {
        onAction(AgentsAction.Observe(true))
        onStopOrDispose { onAction(AgentsAction.Observe(false)) }
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(CoreDesignsystemR.string.navigation_agents)) },
                    navigationIcon = { UpButton() },
                    actions = {
                        ActionIcon(
                            CoreDesignsystemR.drawable.ic_refresh,
                            stringResource(R.string.agents_refresh),
                            enabled = state.connected && !state.busy,
                        ) {
                            onAction(AgentsAction.Refresh)
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    AgentsTab.entries.forEach { tab ->
                        Tab(
                            state.tab == tab,
                            { onAction(AgentsAction.Tab(tab)) },
                            text = {
                                Text(
                                    stringResource(
                                        when (tab) {
                                            AgentsTab.Lineage -> R.string.agents_lineage
                                            AgentsTab.Members -> R.string.agents_members
                                            AgentsTab.Tasks -> R.string.agents_tasks
                                            AgentsTab.Messages -> R.string.agents_messages
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(inset, Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Large),
            ) {
                if (!state.connected)
                    item("offline") { Text(stringResource(R.string.agents_offline)) }
                if (state.sessionId == null)
                    item("select") { Text(stringResource(R.string.agents_select_session)) }
                state.error?.let {
                    item("error") { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                state.notice?.let { item("notice") { Text(it) } }
                when (state.tab) {
                    AgentsTab.Lineage -> Unit
                    AgentsTab.Lineage -> {
                        state.parent?.let { parent ->
                            item("parent") {
                                TextButton(
                                    { onAction(AgentsAction.OpenSession(parent.parentSessionId)) },
                                ) {
                                    Text(stringResource(R.string.agents_open_parent))
                                }
                            }
                        }
                        if (state.nodes.isEmpty())
                            item("empty") { Text(stringResource(R.string.agents_no_children)) }
                        items(
                            state.nodes,
                            key = { it.address.childSessionId },
                            contentType = { "agent" },
                        ) { node ->
                            ElevatedCard {
                                Column(
                                    Modifier.padding(
                                        start = Spacing.Large + Spacing.Medium * node.depth,
                                        end = Spacing.Large,
                                        top = Spacing.Medium,
                                        bottom = Spacing.Medium,
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                                ) {
                                    Text(node.label, style = MaterialTheme.typography.titleMedium)
                                    node.title?.takeIf { it != node.label }?.let { Text(it) }
                                    Text(
                                        "${modeLabel(node.address.mode)} · ${statusLabel(node.status)} · ${node.elapsedMs / 1000}s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(Spacing.Small, Alignment.End),
                                    ) {
                                        if (node.address.mode != SubagentMode.External)
                                            TextButton(
                                                { onAction(AgentsAction.Open(node.address)) },
                                            ) {
                                                Text(stringResource(R.string.agents_open))
                                            }
                                        if (
                                            node.status == "running" &&
                                                node.address.mode == SubagentMode.Continuable
                                        )
                                            TextButton(
                                                { onAction(AgentsAction.Interrupt(node.address)) },
                                                enabled = !state.busy,
                                            ) {
                                                Text(stringResource(R.string.agents_interrupt))
                                            }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (state.team == null)
                            item("noTeam") {
                                Column {
                                    Text(stringResource(R.string.agents_no_team))
                                    if (state.bundleAvailable && !state.bundleEnabled)
                                        OutlinedButton(
                                            { onAction(AgentsAction.EnableTeam) },
                                            enabled = !state.busy,
                                        ) {
                                            Text(stringResource(R.string.agents_enable_team))
                                        }
                                }
                            }
                        state.team?.failure?.let {
                            item("teamFailure") {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        when (state.tab) {
                            AgentsTab.Lineage -> Unit
                            AgentsTab.Members ->
                                items(state.team?.members.orEmpty(), key = { it.id }) { member ->
                                    Column {
                                        Text(
                                            member.name,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            if (member.id in state.running)
                                                stringResource(R.string.agents_running)
                                            else statusLabel(member.phase),
                                        )
                                        member.error?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                        if (member.phase == "active")
                                            TextButton(
                                                {
                                                    if (member.lead)
                                                        onAction(
                                                            AgentsAction.OpenSession(member.id),
                                                        )
                                                    else
                                                        state.leadId?.let {
                                                            onAction(
                                                                AgentsAction.Open(
                                                                    SubagentAddress(
                                                                        it,
                                                                        member.id,
                                                                        SubagentMode.Continuable,
                                                                    ),
                                                                ),
                                                            )
                                                        }
                                                },
                                            ) {
                                                Text(stringResource(R.string.agents_open))
                                            }
                                    }
                                }
                            AgentsTab.Tasks ->
                                items(state.team?.tasks.orEmpty(), key = { it.id }) { task ->
                                    ElevatedCard {
                                        Column(
                                            Modifier.padding(Spacing.Large),
                                            verticalArrangement =
                                                Arrangement.spacedBy(Spacing.Small),
                                        ) {
                                            Text(
                                                task.subject,
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text("${task.id} · ${statusLabel(task.status)}")
                                            Text(task.description)
                                            task.owner?.let {
                                                Text(stringResource(R.string.agents_task_owner, it))
                                            }
                                            if (task.blockedBy.isNotEmpty())
                                                Text(
                                                    stringResource(
                                                        R.string.agents_blocked_by,
                                                        task.blockedBy.joinToString(),
                                                    ),
                                                )
                                            if (task.scopes.isNotEmpty())
                                                Text(
                                                    stringResource(
                                                        R.string.agents_write_scopes,
                                                        task.scopes.joinToString(),
                                                    ),
                                                )
                                            task.warnings.forEach {
                                                Text(it, color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            AgentsTab.Messages -> {
                                item("reloadMessages") {
                                    OutlinedButton({ onAction(AgentsAction.LoadMessages) }) {
                                        Text(stringResource(R.string.agents_load_messages))
                                    }
                                    if (state.unreadable.isNotEmpty())
                                        Text(
                                            stringResource(
                                                R.string.agents_incomplete,
                                                state.unreadable.joinToString(),
                                            ),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                }
                                items(state.messages, key = { "${it.recipient}:${it.seq}" }) {
                                    message ->
                                    Column {
                                        Text(
                                            "${message.senderName ?: message.sender.orEmpty()} → ${message.recipient}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(message.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: SubagentMode) =
    stringResource(
        when (mode) {
            SubagentMode.OneShot -> R.string.agents_once
            SubagentMode.Continuable -> R.string.agents_continuable
            SubagentMode.External -> R.string.agents_external
            SubagentMode.Unknown -> R.string.agents_unknown
        },
    )

@Composable
private fun statusLabel(status: String) =
    stringResource(
        when (status) {
            "running",
            "in_progress" -> R.string.agents_running
            "completed" -> R.string.agents_completed
            "failed" -> R.string.agents_failure
            "provisioning" -> R.string.agents_preparing
            "pending" -> R.string.agents_pending
            else -> R.string.agents_idle
        },
    )

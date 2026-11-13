package dev.fughetta.dshcompose.ui.engine_settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.plugins.CordisPlugin
import dev.fughetta.dshcompose.domain.plugins.CordisRunFailure
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.ReadableWidth
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton

/**
 * The panel's two groups: the plugins of the conversation open in the chat, then everyone else's.
 * Within each, the ones waiting for an answer first, otherwise the Host's order.
 */
internal fun cordisGroups(
    plugins: List<CordisPlugin>,
    currentSessionId: String?,
): Pair<List<CordisPlugin>, List<CordisPlugin>> {
    fun blockingFirst(rows: List<CordisPlugin>) =
        rows.filter { it.status == "awaiting-approval" } +
            rows.filter { it.status != "awaiting-approval" }
    val (mine, others) =
        plugins.partition { currentSessionId != null && it.agentId == currentSessionId }
    return blockingFirst(mine) to blockingFirst(others)
}

@Composable
private fun cordisStatusLabel(status: String) =
    stringResource(
        when (status) {
            "awaiting-approval" -> R.string.cordis_status_pending
            "starting" -> R.string.cordis_status_starting
            "running" -> R.string.cordis_status_running
            "failed" -> R.string.cordis_status_failed
            else -> R.string.cordis_status_idle
        },
    )

@Composable
private fun cordisStatusColor(status: String) =
    when (status) {
        "failed" -> MaterialTheme.colorScheme.error
        "awaiting-approval" -> MaterialTheme.colorScheme.tertiary
        "running" -> MaterialTheme.colorScheme.primary
        "starting" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

@Composable
private fun cordisFailureText(failure: CordisRunFailure) =
    stringResource(
        when (failure.phase) {
            "approval" -> R.string.cordis_failure_approval
            "host-load",
            "host-apply" -> R.string.cordis_failure_host
            "client-load",
            "client-apply" -> R.string.cordis_failure_client
            "client-render" -> R.string.cordis_failure_render
            else -> R.string.cordis_failure_other
        },
    )

/** 「Cordis」: every dynamic plugin on the active host, natively. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CordisScreen(
    state: PluginManagementState,
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    onAction: (PluginManagementAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(Unit) {
        onAction(PluginManagementAction.Observe(true))
        onStopOrDispose { onAction(PluginManagementAction.Observe(false)) }
    }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removing by remember { mutableStateOf<CordisPlugin?>(null) }
    val (current, others) =
        remember(state.cordis, currentSessionId) { cordisGroups(state.cordis, currentSessionId) }
    val titled = current.isNotEmpty() && others.isNotEmpty()
    Scaffold(
        modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.cordis_title))
                        Text(
                            stringResource(R.string.cordis_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { UpButton() },
                actions = {
                    ActionIcon(
                        R.drawable.ic_refresh,
                        stringResource(R.string.engine_refresh),
                        enabled = !state.busy && state.cordisReadable,
                    ) {
                        onAction(PluginManagementAction.Refresh)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).wrapContentWidth().widthIn(max = ReadableWidth),
            contentPadding = PaddingValues(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            if (state.loading || state.busy)
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            when {
                !state.connected ->
                    item {
                        CordisMessage(
                            stringResource(R.string.cordis_offline),
                            stringResource(R.string.cordis_offline_hint),
                        )
                    }
                !state.cordisReadable ->
                    item {
                        CordisMessage(
                            stringResource(R.string.cordis_unavailable),
                            stringResource(R.string.cordis_unavailable_hint),
                        )
                    }
                state.cordis.isEmpty() && !state.loading ->
                    item {
                        CordisMessage(
                            stringResource(R.string.cordis_empty),
                            stringResource(R.string.cordis_empty_hint),
                        )
                    }
                else -> {
                    if (titled)
                        item { CordisGroupTitle(stringResource(R.string.cordis_current_chat)) }
                    items(current, key = { "current:${it.agentId}:${it.pluginId}" }) {
                        CordisRow(it) { selected = it.agentId to it.pluginId }
                    }
                    if (titled)
                        item { CordisGroupTitle(stringResource(R.string.cordis_other_chats)) }
                    items(others, key = { "other:${it.agentId}:${it.pluginId}" }) {
                        CordisRow(it) { selected = it.agentId to it.pluginId }
                    }
                }
            }
        }
    }
    // The sheet follows the live inventory; a removed plugin closes it.
    val plugin = selected?.let { (agent, id) ->
        state.cordis.firstOrNull { it.agentId == agent && it.pluginId == id }
    }
    LaunchedEffect(plugin, state.loading) {
        if (selected != null && plugin == null && !state.loading) selected = null
    }
    plugin?.let { row ->
        CordisPluginSheet(
            plugin = row,
            sessionTitle =
                sessions.firstOrNull { it.sessionId == row.agentId }?.title?.trim().orEmpty(),
            busy = state.busy,
            onDismiss = { selected = null },
            onStop = {
                onAction(
                    PluginManagementAction.Request(
                        PluginConfirmation.Stop(row.agentId, row.pluginId),
                    ),
                )
                onAction(PluginManagementAction.Confirm)
            },
            onRemove = { removing = row },
        )
    }
    removing?.let { row ->
        val versions = row.packages.size
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.cordis_remove_title, row.name)) },
            text = {
                Text(
                    if (versions > 1) stringResource(R.string.cordis_remove_versions, versions)
                    else stringResource(R.string.cordis_remove_one),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        removing = null
                        onAction(
                            PluginManagementAction.Request(
                                PluginConfirmation.Undefine(row.agentId, row.pluginId),
                            ),
                        )
                        onAction(PluginManagementAction.Confirm)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) {
                    Text(stringResource(R.string.engine_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) {
                    Text(stringResource(R.string.engine_cancel))
                }
            },
        )
    }
}

@Composable
private fun CordisGroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.Medium),
    )
}

@Composable
private fun CordisMessage(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.Large * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).clip(MaterialTheme.shapes.extraLarge)) {
        Surface(Modifier.fillMaxSize(), color = color) {}
    }
}

/** One plugin: status dot, name, purpose, and the status in words. */
@Composable
private fun CordisRow(plugin: CordisPlugin, onClick: () -> Unit) {
    val color = cordisStatusColor(plugin.status)
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { StatusDot(color) },
            content = { Text(plugin.name) },
            supportingContent =
                plugin.purpose.takeIf(String::isNotBlank)?.let { purpose ->
                    { Text(purpose, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                },
            trailingContent = {
                Text(
                    cordisStatusLabel(plugin.status),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (plugin.status == "failed" || plugin.status == "awaiting-approval") color
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

/** One plugin: what it is, its state, and what can be done from the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CordisPluginSheet(
    plugin: CordisPlugin,
    sessionTitle: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    var details by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Large)
                .padding(bottom = Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            Text(plugin.name, style = MaterialTheme.typography.titleLarge)
            if (plugin.purpose.isNotBlank())
                Text(plugin.purpose, style = MaterialTheme.typography.bodyMedium)
            plugin.latestRun
                ?.failure
                ?.takeIf { plugin.status == "failed" }
                ?.let { failure ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                cordisFailureText(failure),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            if (failure.message.isNotBlank())
                                TextButton(onClick = { details = !details }) {
                                    Text(
                                        stringResource(
                                            if (details) R.string.cordis_collapse
                                            else R.string.cordis_details,
                                        ),
                                    )
                                }
                        }
                        if (details && failure.message.isNotBlank())
                            SelectionContainer {
                                Text(
                                    failure.message,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            OutlinedCard(Modifier.fillMaxWidth()) {
                CordisFact(
                    stringResource(R.string.cordis_fact_status),
                    cordisStatusLabel(plugin.status),
                )
                CordisFact(
                    stringResource(R.string.cordis_fact_chat),
                    sessionTitle.ifEmpty { stringResource(R.string.cordis_untitled_chat) },
                )
                if (plugin.packages.size > 1)
                    CordisFact(
                        stringResource(R.string.engine_version),
                        stringResource(R.string.cordis_version_count, plugin.packages.size),
                    )
                plugin.shown?.let { shown ->
                    CordisFact(
                        stringResource(R.string.cordis_fact_makeup),
                        when (shown.hasHostHalf to shown.hasClientHalf) {
                            true to true -> stringResource(R.string.cordis_makeup_both)
                            true to false -> stringResource(R.string.cordis_makeup_host)
                            false to true -> stringResource(R.string.cordis_makeup_client)
                            else -> "—"
                        },
                    )
                }
            }
            if (plugin.hasInterface)
                Text(
                    stringResource(R.string.cordis_interface_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                OutlinedButton(
                    onClick = onStop,
                    enabled = !busy && plugin.running,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.engine_stop))
                }
                OutlinedButton(
                    onClick = onRemove,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.engine_remove))
                }
            }
        }
    }
}

@Composable
private fun CordisFact(title: String, value: String) {
    ListItem(
        content = { Text(title) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodyMedium) },
    )
}

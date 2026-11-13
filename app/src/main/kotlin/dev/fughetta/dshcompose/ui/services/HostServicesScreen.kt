package dev.fughetta.dshcompose.ui.services

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostServicesScreen(state: AppState, onAction: (AppAction) -> Unit) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var localOnly by rememberSaveable { mutableStateOf(false) }
    state.remoteHosts
        .firstOrNull { it.id == removing }
        ?.let { host ->
            val label =
                if (localOnly) R.string.compose_host_forget else R.string.compose_host_remove
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(label)) },
                text = {
                    Text(
                        stringResource(
                            if (localOnly) R.string.compose_host_forget_warning
                            else R.string.compose_host_remove_warning,
                            host.name,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !state.remoteHostsBusy,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        onClick = {
                            removing = null
                            onAction(
                                if (localOnly) AppAction.ForgetRemoteHost(host.id)
                                else AppAction.RemoveRemoteHost(host.id),
                            )
                        },
                    ) {
                        Text(stringResource(label))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.navigation_compose_services)) },
                navigationIcon = { UpButton() },
                actions = {
                    ActionIcon(
                        R.drawable.ic_add,
                        stringResource(R.string.compose_host_add),
                        enabled = !state.remoteHostsBusy,
                    ) {
                        onAction(AppAction.AddRemoteHost)
                    }
                },
            )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = inset, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.remoteHostsBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.remoteHostEditor == null)
                    state.remoteHostError?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                if (state.remoteHosts.isEmpty())
                    item {
                        Text(
                            stringResource(R.string.compose_hosts_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                items(state.remoteHosts, key = { it.id }) { host ->
                    var menu by remember { mutableStateOf(false) }
                    val connected = state.hostIdentity == host.id && state.connectivity.connected
                    val pairing = state.remoteHostsBusy && state.remoteHostEditor?.id == host.id
                    val status =
                        stringResource(
                            when {
                                pairing -> R.string.compose_host_pairing
                                connected -> R.string.compose_host_connected
                                else -> R.string.compose_host_idle
                            },
                        )
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        border =
                            BorderStroke(
                                1.dp,
                                if (connected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    ) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(host.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        host.url.removePrefix("https://").removePrefix("http://"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Badge(
                                            containerColor =
                                                if (connected || pairing)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                        )
                                        Text(
                                            status,
                                            style = MaterialTheme.typography.labelMedium,
                                            color =
                                                if (connected || pairing)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Box {
                                    IconButton(
                                        onClick = { menu = true },
                                        enabled = !state.remoteHostsBusy,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_more),
                                            stringResource(R.string.more_actions),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menu,
                                        onDismissRequest = { menu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(R.string.compose_host_remove))
                                            },
                                            colors =
                                                MenuDefaults.itemColors(
                                                    textColor = MaterialTheme.colorScheme.error,
                                                ),
                                            onClick = {
                                                menu = false
                                                localOnly = false
                                                removing = host.id
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(R.string.compose_host_forget))
                                            },
                                            colors =
                                                MenuDefaults.itemColors(
                                                    textColor = MaterialTheme.colorScheme.error,
                                                ),
                                            onClick = {
                                                menu = false
                                                localOnly = true
                                                removing = host.id
                                            },
                                        )
                                    }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { onAction(AppAction.SelectHost(host.id)) },
                                    enabled = !state.remoteHostsBusy && !connected,
                                ) {
                                    Text(
                                        stringResource(
                                            if (connected) R.string.compose_host_connected
                                            else R.string.compose_host_connect_action,
                                        ),
                                    )
                                }
                                TextButton(
                                    onClick = { onAction(AppAction.RepairRemoteHost(host.id)) },
                                    enabled = !state.remoteHostsBusy,
                                ) {
                                    Text(stringResource(R.string.compose_host_repair))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

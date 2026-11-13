package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.runtime.phaseColor
import dev.fughetta.dshcompose.ui.runtime.phaseName

/** A host's name and its live state, as the drawer pill and the host sheet show them. */
private data class HostStatus(val name: String, val label: String, val tone: Color)

@Composable
private fun hostStatus(state: AppState, host: String): HostStatus? {
    if (host == "local")
        return HostStatus(
            stringResource(R.string.host_this_phone),
            phaseName(state.engineControls.phase),
            phaseColor(state.engineControls.phase),
        )
    state.remoteHosts
        .firstOrNull { it.id == host }
        ?.let { config ->
            val connected = state.hostIdentity == host && state.connectionLive
            return HostStatus(
                config.name,
                stringResource(
                    if (connected) R.string.compose_host_connected else R.string.compose_host_idle,
                ),
                if (connected) MaterialTheme.extendedColors.success
                else MaterialTheme.colorScheme.outline,
            )
        }
    return null
}

@Composable
private fun StatusDot(tone: Color) {
    Box(Modifier.size(7.dp).background(tone, CircleShape))
}

/** The drawer host name and status open the host switcher. */
@Composable
internal fun HostPill(
    state: AppState,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = state.hostIdentity
    val status =
        hostStatus(state, host)
            ?: HostStatus(
                stringResource(R.string.host_remote),
                stringResource(R.string.compose_host_idle),
                MaterialTheme.colorScheme.outline,
            )
    val switchLabel = stringResource(R.string.host_current, status.name, status.label)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onSwitch, role = Role.Button)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f, fill = false).clearAndSetSemantics {
                    contentDescription = switchLabel
                },
            ) {
                Text(
                    status.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status.tone)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                null,
                Modifier.size(18.dp).rotate(90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Saved services show connection status when selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostSheet(
    state: AppState,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = state.hostIdentity
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.host_switch),
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item("local") {
                HostRow(
                    stringResource(R.string.host_this_phone),
                    stringResource(
                        if (state.engineControls.phase == "installing")
                            R.string.engine_install_view_progress
                        else R.string.host_this_phone_detail,
                    ),
                    hostStatus(state, "local"),
                    active == "local",
                ) {
                    onPick("local")
                }
            }
            items(state.remoteHosts, key = { it.id }) { host ->
                HostRow(
                    host.name,
                    stringResource(R.string.compose_host_address, host.url),
                    hostStatus(state, host.id),
                    active == host.id,
                ) {
                    onPick(host.id)
                }
            }
            item {
                ListItem(
                    content = { Text(stringResource(R.string.host_manage)) },
                    leadingContent = {
                        Icon(
                            painterResource(
                                if (state.remoteHosts.isEmpty()) R.drawable.ic_add
                                else R.drawable.ic_settings,
                            ),
                            null,
                        )
                    },
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = onManage),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun HostRow(
    title: String,
    subtitle: String,
    status: HostStatus?,
    active: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        content = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(it.tone)
                        Spacer(Modifier.width(6.dp))
                        Text(it.label)
                    }
                }
            }
        },
        trailingContent = { RadioButton(selected = active, onClick = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

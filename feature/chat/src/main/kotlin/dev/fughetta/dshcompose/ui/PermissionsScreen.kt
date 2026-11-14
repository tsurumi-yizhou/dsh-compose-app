package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.permissions.PermissionOption
import dev.fughetta.dshcompose.feature.chat.R

@Composable
private fun permissionName(value: String, fallback: String = value): String =
    when (value) {
        "read-only" -> stringResource(R.string.permission_read_only)
        "workspace-write" -> stringResource(R.string.permission_workspace_write)
        "danger-full-access" -> stringResource(R.string.permission_full)
        "auto" -> stringResource(R.string.permission_auto)
        "custom" -> stringResource(R.string.permission_custom)
        else -> fallback
    }

@Composable
private fun permissionHint(option: PermissionOption): String? =
    when (option.value) {
        "read-only" -> stringResource(R.string.permission_read_only_hint)
        "workspace-write" -> stringResource(R.string.permission_workspace_write_hint)
        "danger-full-access" -> stringResource(R.string.permission_full_hint)
        "auto" -> stringResource(R.string.permission_auto_hint)
        else -> option.description
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(state: PermissionsUiState, onAction: (AppAction) -> Unit) {
    LifecycleStartEffect(state.sessionId) {
        onAction(AppAction.ObservePermissions(true))
        onStopOrDispose { onAction(AppAction.ObservePermissions(false)) }
    }
    val snapshot = state.snapshot
    val enabled = !state.loading && !state.busy && !state.stale && state.pending == null
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreDesignsystemR.string.permissions_title)) },
                navigationIcon = { UpButton() },
                actions = {
                    TextButton(
                        onClick = { onAction(AppAction.RefreshPermissions) },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(CoreDesignsystemR.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = inset, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(stringResource(R.string.permissions_description)) }
                if (state.loading || state.busy)
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.notice?.let { item { Text(it) } }
                if (state.sessionId != null && snapshot != null) {
                    item {
                        Text(
                            stringResource(R.string.permissions_current),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (snapshot.current == "custom")
                        item { Text(stringResource(R.string.permission_custom_hint)) }
                    if (snapshot.current == null || state.stale)
                        item { Text(stringResource(R.string.permissions_unknown)) }
                    item(key = "current:options") {
                        Column(
                            Modifier.selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            snapshot.catalog.options.forEach { option ->
                                PermissionRow(
                                    option,
                                    !state.stale && snapshot.current == option.value,
                                    enabled,
                                ) {
                                    onAction(
                                        AppAction.SelectPermission(
                                            option.value,
                                            PermissionTarget.Current,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                if (snapshot != null) {
                    item {
                        Text(
                            stringResource(R.string.permissions_defaults),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    if (snapshot.defaults?.writable != true)
                        item { Text(stringResource(R.string.permissions_default_readonly)) }
                    item(key = "default:options") {
                        Column(
                            Modifier.selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            snapshot.catalog.defaultOptions.forEach { option ->
                                PermissionRow(
                                    option,
                                    !state.stale && snapshot.defaultValue == option.value,
                                    enabled && snapshot.defaults?.writable == true,
                                ) {
                                    onAction(
                                        AppAction.SelectPermission(
                                            option.value,
                                            PermissionTarget.Default,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.pending?.let { pending ->
        val full = pending.value == "danger-full-access"
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CancelPermission) },
            title = {
                Text(
                    stringResource(
                        if (full) R.string.permission_full_confirm_title
                        else R.string.permission_auto_confirm_title,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            if (pending.target == PermissionTarget.Current)
                                R.string.permissions_current
                            else R.string.permissions_defaults,
                        ),
                    )
                    Text(
                        stringResource(
                            if (full) R.string.permission_full_confirm_body
                            else R.string.permission_auto_confirm_body,
                        ),
                    )
                    if (!full)
                        Row(
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = pending.remember,
                                    role = Role.Checkbox,
                                    onValueChange = { onAction(AppAction.RememberAutoReview(it)) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(pending.remember, onCheckedChange = null)
                            Text(stringResource(R.string.permission_dont_ask))
                        }
                }
            },
            confirmButton = {
                TextButton(onClick = { onAction(AppAction.ConfirmPermission) }) {
                    Text(
                        stringResource(
                            if (full) R.string.permission_full_confirm_action
                            else R.string.permission_auto_confirm_action,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AppAction.CancelPermission) }) {
                    Text(stringResource(CoreDesignsystemR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(
    option: PermissionOption,
    selected: Boolean,
    enabled: Boolean,
    select: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier.selectable(
                selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = select,
            ),
        leadingContent = { RadioButton(selected, onClick = null, enabled = enabled) },
        supportingContent = { permissionHint(option)?.let { Text(it) } },
    ) {
        Text(permissionName(option.value, option.name))
    }
}

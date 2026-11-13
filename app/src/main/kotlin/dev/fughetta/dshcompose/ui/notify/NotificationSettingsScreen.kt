package dev.fughetta.dshcompose.ui.notify

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.ReadablePane
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.SwitchListItem
import dev.fughetta.dshcompose.ui.UpButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    state: NotificationSettingsState,
    onAction: (NotificationSettingsAction) -> Unit,
    claimPermission: (String) -> Boolean,
) {
    val latestAction by rememberUpdatedState(onAction)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latestAction(NotificationSettingsAction.Refresh)
        }
        lifecycle.addObserver(observer)
        latestAction(NotificationSettingsAction.Refresh)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var permissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            latestAction(NotificationSettingsAction.PermissionResult(permissionId, granted))
            permissionId = null
        }
    LaunchedEffect(state.permissionRequest) {
        state.permissionRequest?.let { id ->
            if (claimPermission(id)) {
                permissionId = id
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) {
                    latestAction(NotificationSettingsAction.PermissionResult(id, false))
                    permissionId = null
                }
            }
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.navigation_notifications)) },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding), base = 0.dp) { inset ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(inset, Spacing.Small),
            ) {
                item("statusSection") { Section(R.string.notification_controls_status_section) }
                item("master") {
                    Preference(R.string.notification_controls_enable, state.enabled, !state.busy) {
                        onAction(
                            NotificationSettingsAction.Preference(
                                NotificationPreference.Enabled,
                                it,
                            ),
                        )
                    }
                }
                state.error?.let {
                    item("error") {
                        Text(
                            it,
                            Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.busy || !state.loaded)
                    item("progress") {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().padding(horizontal = Spacing.Large),
                        )
                    }
                if (state.loaded && !state.systemEnabled)
                    item("blocked") {
                        Column(
                            Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                        ) {
                            Text(
                                stringResource(R.string.notification_controls_system_blocked),
                                color = MaterialTheme.colorScheme.error,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                            ) {
                                OutlinedButton(
                                    { onAction(NotificationSettingsAction.RequestPermission) },
                                ) {
                                    Text(stringResource(R.string.notification_controls_grant))
                                }
                                TextButton({ onAction(NotificationSettingsAction.OpenSystem()) }) {
                                    Text(
                                        stringResource(
                                            R.string.notification_controls_system_settings,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                if (state.enabled) {
                    item("eventsSection") { Section(R.string.notification_controls_events_section) }
                    listOf(
                            Triple(
                                NotificationPreference.Done,
                                R.string.notification_controls_done_setting,
                                state.done,
                            ),
                            Triple(
                                NotificationPreference.Failed,
                                R.string.notification_controls_failed_setting,
                                state.failed,
                            ),
                            Triple(
                                NotificationPreference.Approval,
                                R.string.notification_controls_approval_setting,
                                state.approval,
                            ),
                            Triple(
                                NotificationPreference.Question,
                                R.string.notification_controls_question_setting,
                                state.question,
                            ),
                        )
                        .forEach { (key, label, enabled) ->
                            item(key) {
                                Preference(label, enabled, !state.busy) {
                                    onAction(NotificationSettingsAction.Preference(key, it))
                                }
                            }
                        }
                    if (state.loaded && (!state.doneChannel || !state.attentionChannel))
                        item("channels") {
                            Column(
                                Modifier.padding(
                                    horizontal = Spacing.Large,
                                    vertical = Spacing.Small,
                                ),
                            ) {
                                Text(
                                    stringResource(R.string.notification_controls_channels_blocked),
                                )
                                if (!state.doneChannel)
                                    TextButton(
                                        { onAction(NotificationSettingsAction.OpenSystem("done")) },
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.notification_controls_done_channel,
                                            ),
                                        )
                                    }
                                if (!state.attentionChannel)
                                    TextButton(
                                        {
                                            onAction(
                                                NotificationSettingsAction.OpenSystem("attention"),
                                            )
                                        },
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.notification_controls_attention_channel,
                                            ),
                                        )
                                    }
                            }
                        }
                    item("liveSection") { Section(R.string.notification_controls_live_section) }
                    item("live") {
                        Preference(
                            R.string.notification_controls_live_setting,
                            state.live,
                            !state.busy,
                            stringResource(R.string.notification_controls_live_help),
                        ) {
                            onAction(
                                NotificationSettingsAction.Preference(
                                    NotificationPreference.Live,
                                    it,
                                ),
                            )
                        }
                    }
                    if (state.live && state.loaded)
                        item("liveStatus") {
                            Column(
                                Modifier.padding(
                                    horizontal = Spacing.Large,
                                    vertical = Spacing.Small,
                                ),
                            ) {
                                Text(
                                    stringResource(
                                        when {
                                            !state.liveSupported ->
                                                R.string.notification_controls_live_unavailable
                                            state.liveAllowed ->
                                                R.string.notification_controls_live_allowed
                                            else -> R.string.notification_controls_live_blocked
                                        },
                                    ),
                                )
                                if (state.liveSupported && !state.liveAllowed)
                                    TextButton(
                                        { onAction(NotificationSettingsAction.OpenLiveSystem) },
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.notification_controls_live_system,
                                            ),
                                        )
                                    }
                            }
                        }
                    if (state.live && state.loaded && state.nowBar != NowBarState.NotApplicable)
                        item("nowBar") {
                            Column(
                                Modifier.padding(
                                    horizontal = Spacing.Large,
                                    vertical = Spacing.Small,
                                ),
                            ) {
                                Text(
                                    stringResource(
                                        if (state.nowBar == NowBarState.Allowed)
                                            R.string.notification_controls_nowbar_allowed
                                        else R.string.notification_controls_nowbar_blocked,
                                    ),
                                )
                                if (state.nowBar != NowBarState.Allowed) {
                                    if (state.nowBarWritable)
                                        TextButton(
                                            { onAction(NotificationSettingsAction.EnableNowBar) },
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.notification_controls_nowbar_enable,
                                                ),
                                            )
                                        }
                                    TextButton(
                                        { onAction(NotificationSettingsAction.OpenNowBarSystem) },
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.notification_controls_nowbar_developer,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                }
                item("privacy") {
                    Column(
                        Modifier.padding(Spacing.Large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                    ) {
                        Text(
                            stringResource(R.string.notification_controls_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.notification_controls_remote_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        Modifier.padding(
            start = Spacing.Large,
            end = Spacing.Large,
            top = 24.dp,
            bottom = Spacing.Small,
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Preference(
    label: Int,
    checked: Boolean,
    enabled: Boolean,
    supporting: String? = null,
    onChange: (Boolean) -> Unit,
) {
    SwitchListItem(
        stringResource(label),
        checked,
        onChange,
        enabled = enabled,
        supporting = supporting,
    )
}

@Composable
fun NotificationBannerHost(
    banner: NotificationBanner?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    banner ?: return
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(banner.id) {
        kotlinx.coroutines.delay(8_000)
        latestDismiss()
    }
    ElevatedCard(modifier.padding(Spacing.Medium).widthIn(max = 560.dp).fillMaxWidth()) {
        Column(
            Modifier.padding(
                start = Spacing.Large,
                top = Spacing.Large,
                end = Spacing.Small,
                bottom = Spacing.Small,
            ),
        ) {
            Text(banner.title, style = MaterialTheme.typography.titleSmall)
            Text(banner.text, style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                TextButton(onOpen) { Text(stringResource(R.string.notification_controls_open)) }
                TextButton(onDismiss) {
                    Text(stringResource(R.string.notification_controls_dismiss))
                }
            }
        }
    }
}

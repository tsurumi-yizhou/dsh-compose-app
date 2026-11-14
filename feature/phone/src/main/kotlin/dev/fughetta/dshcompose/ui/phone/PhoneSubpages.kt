package dev.fughetta.dshcompose.ui.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.core.phone.R as PhoneR
import dev.fughetta.dshcompose.feature.phone.R
import dev.fughetta.dshcompose.ui.ReadablePane
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.AdaptiveCardGrid
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.SectionCard
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import dev.fughetta.dshcompose.ui.shell.AskAiTopic

/** Shizuku's own download page (official site). */
private const val ShizukuDownloadUrl = "https://shizuku.rikka.app/download/"

/** Silenced from the root confirmation's "don't show again". */
internal const val RootRiskId = "phone.root"

/** One-line state of 不打扰模式 for its row on the overview. */
@Composable
internal fun dndSummary(status: PhoneControlStatus): String {
    val bg = status.background
    if (status.sdk in 1..33) return stringResource(R.string.phone_dnd_requires_android)
    if (bg.mode == "off") return stringResource(R.string.phone_dnd_hidden_screen)
    val channel = modeLabel(bg.mode)
    return if (bg.ready) stringResource(R.string.phone_dnd_channel_ready, channel)
    else
        stringResource(
            R.string.phone_dnd_channel_status,
            channel,
            bg.reason ?: stringResource(R.string.phone_controls_not_ready),
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubpageScaffold(
    title: Int,
    state: PhoneControlsUiState,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val wide = windowTier().atLeastExpanded
    val body: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        state.error?.let { error ->
            item("error") { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        state.notice?.let { notice -> item("notice") { Text(notice) } }
        content()
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = { UpButton() },
                )
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        if (wide) {
            Box(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    Modifier.fillMaxHeight().widthIn(max = ContentWidths.Form).fillMaxWidth(),
                    contentPadding = PaddingValues(Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                    content = body,
                )
            }
        } else {
            ReadablePane(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            ) { inset ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(inset, Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                    content = body,
                )
            }
        }
    }
}

/**
 * 不打扰模式: the agent works on a virtual display through a privileged channel that the person picks
 * here. Nothing on this page picks, authorizes or runs su on its own.
 */
@Composable
internal fun PhoneDndScreen(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
    onAskAi: ((AskAiTopic) -> Unit)?,
) {
    val bg = state.status.background
    val uri = LocalUriHandler.current
    SubpageScaffold(CoreDesignsystemR.string.phone_dnd_title, state) {
        item("help") {
            Text(stringResource(R.string.phone_dnd_subtitle))
            if (state.status.sdk in 1..33)
                Text(
                    stringResource(R.string.phone_dnd_unsupported),
                    color = MaterialTheme.colorScheme.error,
                )
        }
        item("mode") {
            Column(Modifier.selectableGroup()) {
                Text(
                    stringResource(R.string.phone_dnd_channels),
                    style = MaterialTheme.typography.titleMedium,
                )
                bg.supported.forEach { mode ->
                    Choice(modeLabel(mode), bg.mode == mode, !state.busy) {
                        onAction(PhoneControlsAction.BackgroundMode(mode))
                    }
                    channelFor(mode)?.let {
                        Text(
                            stringResource(it),
                            Modifier.padding(start = 56.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item("readiness") {
            Text(
                stringResource(
                    if (bg.ready) R.string.phone_controls_ready
                    else R.string.phone_controls_not_ready,
                ),
            )
            bg.reason?.let { Text(it) }
        }
        item("keep") {
            Toggle(
                stringResource(R.string.phone_controls_keep),
                bg.keep,
                !state.busy && bg.mode != "off",
            ) {
                onAction(PhoneControlsAction.BackgroundKeep(it))
            }
            bg.keepReason?.let { Text(it) }
        }
        if (bg.mode == "shizuku" && !bg.providerRunning)
            item("shizuku") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(stringResource(R.string.phone_dnd_install_shizuku))
                    OutlinedButton({ runCatching { uri.openUri(ShizukuDownloadUrl) } }) {
                        Text(stringResource(R.string.phone_dnd_open_shizuku_site))
                    }
                    Text(stringResource(R.string.phone_dnd_start_shizuku))
                }
            }
        if (bg.mode == "shizuku" || bg.mode == "sui")
            item("provider") {
                Text(
                    stringResource(
                        if (bg.providerRunning) R.string.phone_controls_provider_running
                        else R.string.phone_controls_provider_stopped,
                    ),
                )
                Action(R.string.phone_controls_grant_provider, !state.busy) {
                    onAction(PhoneControlsAction.RequestPrivilegedPermission)
                }
            }
        if (bg.mode == "root")
            item("root") {
                Text(stringResource(R.string.phone_controls_root_help))
                Action(R.string.phone_controls_prepare_root, !state.busy) {
                    onAction(PhoneControlsAction.PrepareRoot)
                }
            }
        if (bg.mode == "adb") {
            item("adbhelp") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(stringResource(R.string.phone_controls_adb_help))
                    listOf(
                            R.string.phone_dnd_developer_options_step,
                            R.string.phone_dnd_wireless_debugging_step,
                            R.string.phone_dnd_start_pairing_step,
                            R.string.phone_dnd_get_pairing_code_step,
                            R.string.phone_dnd_enter_pairing_code_step,
                        )
                        .forEachIndexed { index, step ->
                            Text("${index + 1}. ${stringResource(step)}")
                        }
                    Action(R.string.phone_controls_wireless_debugging) {
                        onAction(PhoneControlsAction.OpenAccess(PhoneAccessKind.WirelessDebugging))
                    }
                }
            }
            item("pair") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        stringResource(
                            if (bg.adbConnected) R.string.phone_controls_adb_connected
                            else R.string.phone_controls_adb_disconnected,
                        ),
                    )
                    Action(R.string.phone_controls_start_pairing, !state.busy) {
                        onAction(PhoneControlsAction.StartAdbPairing)
                    }
                    OutlinedTextField(
                        state.pairingCode,
                        { onAction(PhoneControlsAction.PairingCode(it)) },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.phone_controls_pairing_code)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    OutlinedTextField(
                        state.pairingPort,
                        { onAction(PhoneControlsAction.PairingPortChanged(it)) },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.phone_controls_pairing_port)) },
                        supportingText = { Text(stringResource(R.string.phone_dnd_port_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Action(R.string.phone_controls_pair, !state.busy) {
                        onAction(PhoneControlsAction.PairAdb)
                    }
                    Action(R.string.phone_controls_connect, !state.busy) {
                        onAction(PhoneControlsAction.ConnectAdb)
                    }
                }
            }
        }
        item("requirements") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                Text(
                    stringResource(R.string.phone_dnd_requirements),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("• " + stringResource(R.string.phone_dnd_keep_unlocked))
                Text("• " + stringResource(R.string.phone_dnd_reactivate))
                Text("• " + stringResource(R.string.phone_dnd_keep_debugging))
            }
        }
        onAskAi?.let { ask ->
            item("ask") {
                SubpageLink(
                    stringResource(R.string.ask_ai),
                    stringResource(AskAiTopic.PhoneControl.question),
                ) {
                    ask(AskAiTopic.PhoneControl)
                }
            }
        }
    }
}

private fun channelFor(mode: String) =
    when (mode) {
        "shizuku" -> R.string.phone_dnd_shizuku_for
        "sui" -> R.string.phone_dnd_sui_for
        "root" -> R.string.phone_dnd_root_for
        "adb" -> R.string.phone_dnd_adb_for
        else -> null
    }

/** 解除限制: what is the person's to do by default. Turning one on goes through the hold dialog. */
@Composable
internal fun PhoneUnlocksScreen(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
    onAskAi: ((AskAiTopic) -> Unit)?,
) {
    val wide = windowTier().atLeastExpanded
    SubpageScaffold(CoreDesignsystemR.string.phone_controls_unlocks, state) {
        item("help") { Text(stringResource(R.string.phone_controls_unlock_help)) }
        if (wide)
            item("unlocks") {
                SectionCard(
                    title = stringResource(CoreDesignsystemR.string.phone_controls_unlocks),
                ) {
                    Column(Modifier.padding(horizontal = Spacing.Medium)) {
                        AdaptiveCardGrid(
                            items = PhoneUnlockEntries,
                            minItemWidth = 380.dp,
                        ) { entry ->
                            UnlockRow(entry, state, onAction)
                        }
                    }
                }
            }
        else
            PhoneUnlockEntries.forEach { entry ->
                item(entry.key) { UnlockRow(entry, state, onAction) }
            }
        item("banks") {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.Small)) {
                Text(stringResource(R.string.phone_unlock_banks))
                Text(
                    stringResource(R.string.phone_unlock_banks_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("footnote") {
            Text(
                stringResource(R.string.phone_unlock_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onAskAi?.let { ask ->
            item("ask") {
                SubpageLink(
                    stringResource(R.string.ask_ai),
                    stringResource(AskAiTopic.FullAccess.question),
                ) {
                    ask(AskAiTopic.FullAccess)
                }
            }
        }
    }
}

/** Advice for confirmations and returning; opens only standard system entries. */
@Composable
internal fun BackgroundGuideScreen(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
) {
    SubpageScaffold(CoreDesignsystemR.string.background_guide_title, state) {
        item("body") { Text(stringResource(R.string.background_guide_body)) }
        item("notifications") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.phone_controls_notifications))
                    Text(
                        stringResource(
                            if (state.access.notifications) R.string.phone_controls_granted
                            else R.string.background_guide_notification_blocked,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Action(R.string.phone_controls_open_settings) {
                    onAction(PhoneControlsAction.OpenAccess(PhoneAccessKind.Notifications))
                }
            }
        }
        if (state.access.vendor == "vivo" || state.access.vendor == "xiaomi")
            item("oem") {
                Text(stringResource(R.string.background_guide_oem_hint))
                Action(R.string.background_guide_open_settings) {
                    onAction(PhoneControlsAction.OpenAccess(PhoneAccessKind.Popup))
                }
            }
        item("return") { Text(stringResource(R.string.background_guide_return_hint)) }
        item("details") {
            Action(PhoneR.string.phone_controls_app_details) {
                onAction(PhoneControlsAction.OpenAccess(PhoneAccessKind.AppDetails))
            }
        }
    }
}

/** Shown once, the first time phone control is on. */
@Composable
internal fun BackgroundGuideDialog(
    vendor: String?,
    onAction: (PhoneControlsAction) -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(CoreDesignsystemR.string.background_guide_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                item { Text(stringResource(R.string.background_guide_body)) }
                if (vendor == "vivo" || vendor == "xiaomi")
                    item { Text(stringResource(R.string.background_guide_oem_hint)) }
                item { Text(stringResource(R.string.background_guide_return_hint)) }
            }
        },
        confirmButton = {
            Button(
                {
                    onDone()
                    onAction(PhoneControlsAction.OpenAccess(PhoneAccessKind.AppDetails))
                },
            ) {
                Text(stringResource(R.string.background_guide_open_settings))
            }
        },
        dismissButton = {
            TextButton(onDone) { Text(stringResource(R.string.background_guide_got_it)) }
        },
    )
}

/** The pending risk confirmation, with a way to ask the guide about it first. */
@Composable
internal fun PhoneRiskDialog(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
    silencedRisks: Set<String>,
    onSilenceRisk: (String) -> Unit,
    onAskAi: ((AskAiTopic) -> Unit)?,
) {
    val risk = state.risk ?: return
    val silencable = risk.kind == PhoneRiskKind.Root
    if (silencable && RootRiskId in silencedRisks) {
        LaunchedEffect(risk) { onAction(PhoneControlsAction.ConfirmRisk) }
        return
    }
    var silence by rememberSaveable(risk) { mutableStateOf(true) }
    val topic =
        when (risk.kind) {
            PhoneRiskKind.AlwaysConfirmOff -> AskAiTopic.PhoneAlways
            PhoneRiskKind.AppRule -> AskAiTopic.AppRules
            PhoneRiskKind.Disclosure,
            PhoneRiskKind.Root -> AskAiTopic.PhoneControl
            PhoneRiskKind.Storage -> AskAiTopic.PhoneFiles
            PhoneRiskKind.Unlock -> AskAiTopic.FullAccess
            PhoneRiskKind.ResetRules -> null
        }
    AlertDialog(
        onDismissRequest = { onAction(PhoneControlsAction.CancelRisk) },
        title = {
            Text(
                stringResource(
                    if (silencable) R.string.risk_root_title
                    else R.string.phone_controls_confirm_title,
                ),
            )
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                item {
                    Text(
                        stringResource(
                            when (risk.kind) {
                                PhoneRiskKind.Root -> R.string.phone_controls_root_warning
                                PhoneRiskKind.Disclosure -> R.string.phone_controls_disclosure
                                PhoneRiskKind.AlwaysConfirmOff ->
                                    R.string.phone_controls_always_warning
                                PhoneRiskKind.Unlock ->
                                    when (risk.unlock) {
                                        "systemPages" ->
                                            R.string.phone_controls_unlock_system_warning
                                        "secretFields" ->
                                            R.string.phone_controls_unlock_secret_warning
                                        else -> R.string.phone_controls_unlock_self_warning
                                    }
                                PhoneRiskKind.AppRule -> R.string.phone_controls_rule_warning
                                PhoneRiskKind.Storage -> R.string.phone_controls_storage_warning
                                PhoneRiskKind.ResetRules -> R.string.phone_controls_reset_warning
                            },
                        ),
                    )
                }
                if (risk.kind == PhoneRiskKind.Unlock)
                    item { Text(stringResource(R.string.phone_controls_unlock_disclaimer)) }
                if (silencable)
                    item {
                        Row(
                            Modifier.fillMaxWidth().toggleable(silence, role = Role.Checkbox) {
                                silence = it
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(silence, onCheckedChange = null)
                            Text(
                                stringResource(R.string.risk_dont_show_again),
                                Modifier.padding(start = Spacing.Small),
                            )
                        }
                    }
                if (onAskAi != null && topic != null)
                    item {
                        TextButton(
                            {
                                onAction(PhoneControlsAction.CancelRisk)
                                onAskAi(topic)
                            },
                        ) {
                            Text(stringResource(R.string.risk_ask_ai))
                        }
                    }
            }
        },
        confirmButton = {
            if (risk.kind == PhoneRiskKind.Unlock)
                HoldToConfirm(!state.busy && !state.status.task.active) {
                    onAction(PhoneControlsAction.ConfirmRisk)
                }
            else
                TextButton(
                    {
                        if (silencable && silence) onSilenceRisk(RootRiskId)
                        onAction(PhoneControlsAction.ConfirmRisk)
                    },
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(
                            if (silencable) R.string.risk_root_action
                            else R.string.phone_controls_accept,
                        ),
                    )
                }
        },
        dismissButton = {
            TextButton({ onAction(PhoneControlsAction.CancelRisk) }) {
                Text(stringResource(R.string.phone_controls_cancel))
            }
        },
    )
}

/** What the model would see on screen right now; this read is not sent to it. */

/** One sensitive-area switch; the label and hint stay paired. */
private data class PhoneUnlockEntry(val key: String, val label: Int, val hint: Int)

private val PhoneUnlockEntries =
    listOf(
        PhoneUnlockEntry(
            "systemPages",
            R.string.phone_controls_unlock_system,
            R.string.phone_unlock_system_hint,
        ),
        PhoneUnlockEntry(
            "secretFields",
            R.string.phone_controls_unlock_secret,
            R.string.phone_unlock_secrets_hint,
        ),
        PhoneUnlockEntry(
            "ownApp",
            R.string.phone_controls_unlock_self,
            R.string.phone_unlock_own_app_hint,
        ),
    )

/** Left description, right switch; wide windows place these side by side in a card. */
@Composable
private fun UnlockRow(
    entry: PhoneUnlockEntry,
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
) {
    val key = entry.key
    val on = state.unlocks[key] == true
    val enabled = !state.busy && (on || !state.status.task.active)
    Row(
        Modifier.fillMaxWidth()
            .toggleable(on, enabled = enabled, role = Role.Switch) {
                onAction(PhoneControlsAction.Unlock(key, it))
            }
            .padding(vertical = Spacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.Large)) {
            Text(stringResource(entry.label))
            Text(
                stringResource(
                    if (!on && state.status.task.active) R.string.phone_unlock_busy else entry.hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(on, onCheckedChange = null, enabled = enabled)
    }
}

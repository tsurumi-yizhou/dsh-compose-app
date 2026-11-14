package dev.fughetta.dshcompose.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.engine.R
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.adaptive.MetricTile
import dev.fughetta.dshcompose.ui.adaptive.MetricTone
import dev.fughetta.dshcompose.ui.adaptive.MetricsBoard
import dev.fughetta.dshcompose.ui.adaptive.SectionCard
import dev.fughetta.dshcompose.ui.adaptive.SectionHeader
import dev.fughetta.dshcompose.ui.adaptive.SettingsRow
import dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider

@Composable
internal fun Overview(
    state: EngineControlsState,
    onAction: (EngineControlsAction) -> Unit,
    wide: Boolean,
) {
    if (wide)
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
        ) {
            LazyColumn(
                Modifier.weight(2f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { InstallationProgress(state) }
                item { EngineMetrics(state) }
                item { ActionsSection(state, onAction) }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { DangerZone(state, onAction) }
            }
        }
    else
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InstallationProgress(state) }
            item { EngineMetrics(state) }
            item { ActionsSection(state, onAction) }
            item { DangerZone(state, onAction) }
        }
}

/** Read-only dashboard: phase, memory, storage and versions as uniform metric tiles. */
@Composable
internal fun EngineMetrics(state: EngineControlsState) {
    // A wider minimum tile keeps the six tiles in a tidy 3×2 grid on the tablet board.
    MetricsBoard(tiles = engineTiles(state), minTileWidth = 240.dp)
}

/** The full build id is a long hyphen-joined string; its first segment is the readable version. */
internal fun shortVersion(buildId: String): String =
    buildId.substringBefore('-').take(16).ifBlank { buildId.take(16) }

@Composable
internal fun engineTiles(state: EngineControlsState): List<MetricTile> {
    val installed = state.installedVersion
    val bundled = state.bundledVersion
    val lowStorage = state.requiredBytes > 0 && state.freeBytes < state.requiredBytes
    return listOf(
        MetricTile(
            label = stringResource(R.string.engine_metric_phase),
            value = phaseName(state.phase),
            tone = phaseTone(state.phase),
        ),
        MetricTile(
            label = stringResource(R.string.engine_metric_memory),
            value = if (state.memoryBytes > 0) size(state.memoryBytes) else "—",
        ),
        MetricTile(
            label = stringResource(R.string.engine_metric_storage),
            value = if (state.freeBytes > 0) size(state.freeBytes) else "—",
            supporting =
                if (state.requiredBytes > 0)
                    stringResource(R.string.engine_metric_storage_need, size(state.requiredBytes))
                else null,
            tone = if (lowStorage) MetricTone.Warning else MetricTone.Neutral,
        ),
        MetricTile(
            label = stringResource(R.string.engine_metric_version),
            value = installed?.let(::shortVersion) ?: "—",
            supporting = installed,
            tone =
                if (installed != null && installed == bundled) MetricTone.Positive
                else MetricTone.Neutral,
        ),
        MetricTile(
            label = stringResource(R.string.engine_metric_bundled),
            value = bundled?.let(::shortVersion) ?: "—",
            supporting = bundled,
        ),
    )
}

@Composable
internal fun phaseTone(phase: String): MetricTone =
    when (phase) {
        "running" -> MetricTone.Positive
        "installing",
        "starting",
        "stopping" -> MetricTone.Warning
        "notInstalled",
        "installed" -> MetricTone.Neutral
        else -> MetricTone.Critical
    }

/** Lifecycle actions as native rows: same actions, same enabled rules, no button row. */
@Composable
internal fun ActionsSection(
    state: EngineControlsState,
    onAction: (EngineControlsAction) -> Unit,
) {
    val actions = lifecycleActions(state.phase)
    SectionCard(title = stringResource(R.string.engine_section_actions)) {
        actions.forEachIndexed { index, (label, action) ->
            if (index > 0) SettingsRowDivider()
            SettingsRow(
                title = stringResource(label),
                enabled = !state.busy,
                chevron = true,
                onClick = { onAction(action) },
            )
        }
    }
}

/** Only the lifecycle actions the current phase allows, so the list does not jump. */
internal fun lifecycleActions(phase: String): List<Pair<Int, EngineControlsAction>> = buildList {
    if (phase !in setOf("installing", "running", "starting", "stopping"))
        add(R.string.engine_runtime_install to EngineControlsAction.Install)
    if (phase in setOf("installed", "error"))
        add(R.string.engine_start to EngineControlsAction.Start)
    if (phase in setOf("running", "starting"))
        add(R.string.engine_runtime_stop to EngineControlsAction.Stop)
    if (phase in setOf("running", "installed"))
        add(R.string.engine_restart to EngineControlsAction.Restart)
}

/**
 * Destructive runtime operations keep their warning frame but use native rows inside. Every row
 * still opens the existing confirmation dialog before anything is deleted.
 */
@Composable
internal fun DangerZone(state: EngineControlsState, onAction: (EngineControlsAction) -> Unit) {
    val error = MaterialTheme.colorScheme.error
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, error.copy(alpha = 0.55f)),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .padding(
                        start = Spacing.Large,
                        end = Spacing.Large,
                        top = Spacing.Large,
                        bottom = Spacing.ExtraSmall,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    title = stringResource(R.string.engine_danger_zone),
                    supporting = stringResource(R.string.engine_danger_zone_help),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(CoreDesignsystemR.drawable.ic_info),
                    contentDescription = null,
                    tint = error,
                )
            }
            ToggleRow(
                title = stringResource(R.string.engine_keep_ui),
                checked = state.keepUiAndSkills,
                enabled = !state.busy,
                onCheckedChange = {
                    onAction(EngineControlsAction.ResetKeep(it, state.keepPluginsAndSettings))
                },
            )
            SettingsRowDivider()
            ToggleRow(
                title = stringResource(R.string.engine_keep_plugins),
                checked = state.keepPluginsAndSettings,
                enabled = !state.busy,
                onCheckedChange = {
                    onAction(EngineControlsAction.ResetKeep(state.keepUiAndSkills, it))
                },
            )
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.engine_runtime_reset),
                supporting = stringResource(R.string.engine_reset_warning),
                enabled = !state.busy && state.phase != "installing",
                chevron = true,
                onClick = { onAction(EngineControlsAction.Reset) },
            )
            SettingsRowDivider()
            SettingsRow(
                title = stringResource(R.string.engine_uninstall),
                supporting = stringResource(R.string.engine_uninstall_warning),
                enabled = !state.busy && state.phase !in setOf("installing", "notInstalled"),
                chevron = true,
                onClick = { onAction(EngineControlsAction.Uninstall) },
            )
            if (state.error == "engine/kept-data-failed") {
                SettingsRowDivider()
                SettingsRow(
                    title = stringResource(R.string.engine_drop_kept),
                    supporting = stringResource(R.string.engine_drop_kept_warning),
                    enabled = !state.busy,
                    chevron = true,
                    onClick = { onAction(EngineControlsAction.DropKeptAndInstall) },
                )
            }
        }
    }
}

/** Native row with a switch: the row toggles, the switch is display-only. */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    SettingsRow(
        title = title,
        supporting = supporting,
        enabled = enabled,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
) {
    ToggleRow(
        title = label,
        checked = checked,
        enabled = enabled,
        supporting = supporting,
        onCheckedChange = onChanged,
    )
}

/** The numbered onboarding steps; Welcome and Finishing stand alone. */
private val CountedSteps =
    listOf(SetupStep.Choice, SetupStep.Appearance, SetupStep.Access, SetupStep.Connect)

/** Keep navigation progress distinct from the runtime installation progress bar. */
@Composable
internal fun OnboardingStepLabel(step: Int, steps: Int) {
    Text(
        text = stringResource(R.string.engine_setup_step, step, steps),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun Primary(
    label: Int,
    action: EngineControlsAction,
    onAction: (EngineControlsAction) -> Unit,
    enabled: Boolean = true,
) {
    Button({ onAction(action) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) {
        Text(stringResource(label))
    }
}

/**
 * First run, one question per page: a 440dp column that fills the screen, the step's words at the
 * top and its buttons at the bottom, the step label above the counted steps.
 */
@Composable
internal fun Setup(
    state: EngineControlsState,
    onAction: (EngineControlsAction) -> Unit,
    welcomeContent: @Composable () -> Unit,
) {
    val index = CountedSteps.indexOf(state.setup)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val minHeight = maxHeight
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                if (index >= 0) OnboardingStepLabel(index + 1, CountedSteps.size)
            }
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight - 32.dp)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.setup == SetupStep.Welcome) Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        when (state.setup) {
                            SetupStep.Welcome -> R.string.engine_welcome
                            SetupStep.Choice -> R.string.engine_choose
                            SetupStep.Appearance -> R.string.engine_setup_appearance
                            SetupStep.Access -> R.string.engine_setup_access
                            SetupStep.Connect -> R.string.engine_setup_connect
                            SetupStep.Finishing -> R.string.engine_setup_finished
                            SetupStep.Finished -> R.string.engine_setup_finished
                        },
                    ),
                    style =
                        if (state.setup == SetupStep.Welcome) MaterialTheme.typography.headlineLarge
                        else MaterialTheme.typography.headlineSmall,
                )
                when (state.setup) {
                    SetupStep.Welcome -> Hint(stringResource(R.string.engine_welcome_help))
                    SetupStep.Choice -> {
                        Hint(stringResource(R.string.engine_local_help))
                        Hint(storage(state))
                        if (state.memoryBytes in 1 until 5L * 1024 * 1024 * 1024)
                            Hint(stringResource(R.string.engine_low_memory))
                    }
                    SetupStep.Appearance -> Hint(stringResource(R.string.engine_appearance_help))
                    SetupStep.Access -> Hint(stringResource(R.string.engine_access_help))
                    SetupStep.Connect -> {
                        Hint(stringResource(R.string.engine_connect_help))
                        if (state.waitingForConnection)
                            Hint(stringResource(R.string.engine_wait_connection))
                    }
                    SetupStep.Finishing -> Unit
                    SetupStep.Finished -> Unit
                }
                Spacer(Modifier.weight(1f))
                when (state.setup) {
                    SetupStep.Welcome -> {
                        welcomeContent()
                        Primary(R.string.engine_continue, EngineControlsAction.Welcome, onAction)
                    }
                    SetupStep.Choice -> {
                        Primary(
                            R.string.engine_choose_local,
                            EngineControlsAction.ChooseLocal,
                            onAction,
                            !state.busy && state.freeBytes >= state.requiredBytes,
                        )
                        Action(
                            R.string.engine_choose_remote,
                            EngineControlsAction.ChooseRemote,
                            onAction,
                        )
                    }
                    SetupStep.Appearance -> {
                        Action(
                            R.string.engine_open_appearance,
                            EngineControlsAction.OpenAppearance,
                            onAction,
                        )
                        Primary(
                            R.string.engine_continue,
                            EngineControlsAction.ContinueSetup,
                            onAction,
                        )
                    }
                    SetupStep.Access -> {
                        Action(
                            R.string.engine_open_access,
                            EngineControlsAction.OpenPhoneAccess,
                            onAction,
                        )
                        Primary(
                            R.string.engine_continue,
                            EngineControlsAction.ContinueSetup,
                            onAction,
                        )
                    }
                    SetupStep.Connect -> {
                        Primary(
                            R.string.engine_open_providers,
                            EngineControlsAction.OpenProviders,
                            onAction,
                            !state.waitingForConnection,
                        )
                        TextButton(
                            { onAction(EngineControlsAction.ContinueSetup) },
                            Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.engine_continue))
                        }
                        TextButton(
                            { onAction(EngineControlsAction.SkipProvider) },
                            Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.engine_later))
                        }
                    }
                    SetupStep.Finishing -> {
                        Primary(
                            R.string.engine_enter,
                            EngineControlsAction.ContinueSetup,
                            onAction,
                        )
                    }
                    SetupStep.Finished -> Unit
                }
            }
        }
    }
}

@Composable
internal fun RestoreDetails(plan: EngineRestorePlan) {
    if (!plan.available) Info(stringResource(R.string.engine_restore_unavailable))
    if (plan.disable.isNotEmpty())
        Info(stringResource(R.string.engine_restore_disable, plan.disable.joinToString(", ")))
    if (plan.enable.isNotEmpty())
        Info(stringResource(R.string.engine_restore_enable, plan.enable.joinToString(", ")))
    if (plan.changed.isNotEmpty())
        Info(stringResource(R.string.engine_restore_changed, plan.changed.joinToString(", ")))
    if (plan.patchChanged) Info(stringResource(R.string.engine_restore_patch))
}

@Composable
internal fun Logs(state: EngineControlsState, onAction: (EngineControlsAction) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(state.logs.lastOrNull()?.sequence, state.followLogs) {
        if (state.followLogs && state.logs.isNotEmpty()) list.scrollToItem(state.logs.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        SwitchRow(
            stringResource(R.string.engine_follow_logs),
            state.followLogs,
            { onAction(EngineControlsAction.FollowLogs(it)) },
        )
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(12.dp),
            ) {
                items(state.logs, key = { it.sequence }) {
                    Text(
                        it.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticPage(
    state: EngineControlsState,
    onAction: (EngineControlsAction) -> Unit,
) {
    val chunks =
        remember(state.diagnosticsPreview) { state.diagnosticsPreview?.chunked(6000).orEmpty() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Info(stringResource(R.string.engine_diagnostics_help))
            Action(
                R.string.engine_collect,
                EngineControlsAction.CollectDiagnostics,
                onAction,
                !state.busy,
            )
        }
        state.diagnosticsPreview?.let { preview ->
            item {
                SwitchRow(
                    stringResource(R.string.engine_reviewed),
                    state.diagnosticsReviewed,
                    {
                        onAction(
                            EngineControlsAction.ReviewDiagnostics(state.diagnosticsRevision, it),
                        )
                    },
                    !state.busy,
                )
            }
            item {
                Action(
                    R.string.engine_share,
                    EngineControlsAction.ShareDiagnostics,
                    onAction,
                    state.diagnosticsReviewed && !state.busy,
                )
                Action(
                    R.string.engine_report_save,
                    EngineControlsAction.SaveDiagnostics,
                    onAction,
                    state.diagnosticsReviewed && !state.busy,
                )
                Action(
                    R.string.engine_publish,
                    EngineControlsAction.PublishDiagnostics,
                    onAction,
                    state.diagnosticsReviewed && !state.busy,
                )
            }
            state.diagnosticsResult?.let { result -> item { Info(result) } }
            // Chunking lets LazyColumn compose only the inspected part of a large diagnostic
            // preview.
            items(chunks) { chunk ->
                SelectionContainer {
                    Text(
                        chunk,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun failureName(kind: String): String =
    stringResource(
        when (kind) {
            "launchFailed" -> R.string.engine_failure_launch
            "startupFailed" -> R.string.engine_failure_startup
            "crashedEarly" -> R.string.engine_failure_early
            "crashedLate" -> R.string.engine_failure_late
            "noSpace" -> R.string.engine_failure_storage
            else -> R.string.engine_failure_killed
        },
    )

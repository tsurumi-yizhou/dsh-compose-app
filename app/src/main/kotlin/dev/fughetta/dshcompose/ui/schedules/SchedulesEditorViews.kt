package dev.fughetta.dshcompose.ui.schedules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.schedules.*
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.EmptyStatePanel
import dev.fughetta.dshcompose.ui.adaptive.SettingsRow
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SchedulesEditor(
    state: SchedulesUiState,
    editor: SchedulesEditorState,
    action: (SchedulesAction) -> Unit,
) {
    var picker by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = { action(SchedulesAction.CloseEditor) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(
                                    if (editor.original == null) R.string.schedules_create
                                    else R.string.schedules_edit,
                                ),
                            )
                        },
                        navigationIcon = {
                            ActionIcon(
                                R.drawable.ic_close,
                                stringResource(R.string.schedules_cancel),
                                enabled = !state.busy,
                            ) {
                                action(SchedulesAction.CloseEditor)
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface {
                        ReadablePane(
                            Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),
                        ) { inset ->
                            Button(
                                onClick = { action(SchedulesAction.Save) },
                                enabled =
                                    !state.busy && !editor.uncertain && editor.validation == null,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = inset, vertical = Spacing.Large),
                            ) {
                                Text(stringResource(R.string.schedules_save))
                            }
                        }
                    }
                },
            ) { padding ->
                ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(inset, Spacing.Large),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                    ) {
                        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        state.error?.let {
                            item {
                                Text(scheduleError(it), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (editor.uncertain)
                            item {
                                Text(
                                    stringResource(R.string.schedules_uncertain),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        item {
                            ScheduleField(
                                editor.title,
                                R.string.schedules_name,
                                !state.busy && !editor.uncertain,
                            ) {
                                action(SchedulesAction.Title(it))
                            }
                        }
                        item {
                            ScheduleField(
                                editor.prompt,
                                R.string.schedules_prompt,
                                !state.busy && !editor.uncertain,
                                singleLine = false,
                            ) {
                                action(SchedulesAction.Prompt(it))
                            }
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                                ScheduleRepeat.entries
                                    .filter {
                                        it != ScheduleRepeat.Custom ||
                                            editor.initialTiming.repeat == it
                                    }
                                    .forEach { repeat ->
                                        FilterChip(
                                            editor.timing.repeat == repeat,
                                            { action(SchedulesAction.Repeat(repeat)) },
                                            enabled = !state.busy && !editor.uncertain,
                                            label = {
                                                Text(stringResource(scheduleRepeatLabel(repeat)))
                                            },
                                        )
                                    }
                            }
                        }
                        val rule = editor.timing
                        if (rule.repeat == ScheduleRepeat.Custom)
                            item {
                                Text(stringResource(R.string.schedules_custom_hint))
                                SelectionContainer { Text(rule.customRule.orEmpty()) }
                            }
                        if (rule.repeat == ScheduleRepeat.Interval) {
                            item {
                                ScheduleField(
                                    rule.interval,
                                    R.string.schedules_interval,
                                    !state.busy,
                                ) {
                                    action(SchedulesAction.Interval(it))
                                }
                            }
                            item {
                                FlowRow {
                                    ScheduleIntervalUnit.entries.forEach { unit ->
                                        FilterChip(
                                            rule.intervalUnit == unit,
                                            { action(SchedulesAction.IntervalUnit(unit)) },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        when (unit) {
                                                            ScheduleIntervalUnit.Seconds ->
                                                                R.string.schedules_seconds
                                                            ScheduleIntervalUnit.Minutes ->
                                                                R.string.schedules_minutes
                                                            ScheduleIntervalUnit.Hours ->
                                                                R.string.schedules_hours
                                                        },
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        } else if (rule.repeat != ScheduleRepeat.Custom) {
                            if (rule.repeat == ScheduleRepeat.Cron)
                                item {
                                    ScheduleField(rule.cron, R.string.schedules_cron, !state.busy) {
                                        action(SchedulesAction.Cron(it))
                                    }
                                    Text(stringResource(R.string.schedules_cron_hint))
                                }
                            else
                                item {
                                    ScheduleField(rule.time, R.string.schedules_time, !state.busy) {
                                        action(SchedulesAction.Time(it))
                                    }
                                }
                            if (rule.repeat == ScheduleRepeat.Once)
                                item {
                                    ScheduleField(rule.date, R.string.schedules_date, !state.busy) {
                                        action(SchedulesAction.Date(it))
                                    }
                                }
                            item {
                                ScheduleField(rule.zone, R.string.schedules_zone, !state.busy) {
                                    action(SchedulesAction.Zone(it))
                                }
                                TextButton(onClick = { picker = "zone" }) {
                                    Text(stringResource(R.string.schedules_choose_zone))
                                }
                            }
                            if (rule.repeat == ScheduleRepeat.Weekly)
                                item {
                                    FlowRow {
                                        (1..7).forEach { day ->
                                            FilterChip(
                                                day in rule.weekdays,
                                                { action(SchedulesAction.Weekday(day)) },
                                                label = {
                                                    Text(
                                                        DayOfWeek.of(day)
                                                            .getDisplayName(
                                                                java.time.format.TextStyle.SHORT,
                                                                java.util.Locale.getDefault(),
                                                            ),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            if (rule.repeat == ScheduleRepeat.Monthly)
                                item {
                                    FlowRow {
                                        (1..31).forEach { day ->
                                            FilterChip(
                                                day in rule.monthDays,
                                                { action(SchedulesAction.MonthDay(day)) },
                                                label = { Text(day.toString()) },
                                            )
                                        }
                                    }
                                    Text(stringResource(R.string.schedules_month_hint))
                                }
                        }
                        item {
                            Text(
                                stringResource(R.string.schedules_target),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            FlowRow {
                                ScheduleTargetKind.entries.forEach { kind ->
                                    FilterChip(
                                        editor.target.kind == kind,
                                        { action(SchedulesAction.Target(kind)) },
                                        enabled = !state.busy,
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (kind) {
                                                        ScheduleTargetKind.Session ->
                                                            R.string.schedules_existing
                                                        ScheduleTargetKind.NewOnce ->
                                                            R.string.schedules_new_once
                                                        ScheduleTargetKind.NewEach ->
                                                            R.string.schedules_new_each
                                                    },
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        if (editor.target.kind == ScheduleTargetKind.Session)
                            item {
                                Text(editor.target.sessionId.orEmpty())
                                TextButton(onClick = { picker = "session" }) {
                                    Text(stringResource(R.string.schedules_choose_session))
                                }
                            }
                        else {
                            item {
                                Text(
                                    editor.target.cwd
                                        ?: stringResource(R.string.schedules_host_default),
                                )
                                TextButton(onClick = { picker = "workspace" }) {
                                    Text(stringResource(R.string.schedules_choose_workspace))
                                }
                            }
                            item {
                                ScheduleField(
                                    editor.target.agentPreset,
                                    R.string.schedules_preset,
                                    !state.busy,
                                ) {
                                    action(SchedulesAction.AgentPreset(it))
                                }
                            }
                        }
                        editor.validation?.let {
                            item {
                                Text(scheduleError(it), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        item {
                            Text(
                                stringResource(R.string.schedules_preview),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            editor.preview.forEach { Text(scheduleInstant(it)) }
                            Text(stringResource(R.string.schedules_preview_hint))
                        }
                    }
                }
            }
        }
    }
    if (editor.discard)
        AlertDialog(
            onDismissRequest = { action(SchedulesAction.KeepEditor) },
            title = { Text(stringResource(R.string.schedules_discard)) },
            confirmButton = {
                TextButton(onClick = { action(SchedulesAction.DiscardEditor) }) {
                    Text(stringResource(R.string.schedules_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { action(SchedulesAction.KeepEditor) }) {
                    Text(stringResource(R.string.schedules_keep))
                }
            },
        )
    picker?.let { kind ->
        SchedulesPicker(kind, state.context, { picker = null }) { key ->
            when (kind) {
                "session" -> action(SchedulesAction.Session(key))
                "workspace" -> action(SchedulesAction.Workspace(key.takeIf(String::isNotEmpty)))
                else -> action(SchedulesAction.Zone(key))
            }
            picker = null
        }
    }
}

@Composable
internal fun ScheduleField(
    value: String,
    label: Int,
    enabled: Boolean,
    singleLine: Boolean = true,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        change,
        Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
    )
}

@Composable
internal fun SchedulesPicker(
    kind: String,
    context: SchedulesContext,
    close: () -> Unit,
    choose: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows by
        produceState<List<Pair<String, String>>>(emptyList(), kind, context, query) {
            value =
                withContext(Dispatchers.Default) {
                    val source =
                        when (kind) {
                            "session" ->
                                context.sessions.map {
                                    it.sessionId to "${it.title.orEmpty()} · ${it.sessionId}"
                                }
                            "workspace" ->
                                listOf("" to "—") +
                                    context.workspaces.map { it.id to "${it.title} · ${it.path}" }
                            else -> ZoneId.getAvailableZoneIds().sorted().map { it to it }
                        }
                    source.filter { it.second.contains(query, true) }
                }
        }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.schedules_choose)) },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    label = { Text(stringResource(R.string.schedules_search)) },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(rows, key = { it.first }) { row ->
                        ListItem(onClick = { choose(row.first) }) { Text(row.second) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = close) { Text(stringResource(R.string.schedules_cancel)) }
        },
    )
}

internal fun scheduleInstant(value: Instant?): String =
    value
        ?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)) ?: "—"

private fun scheduleRepeatLabel(value: ScheduleRepeat): Int =
    when (value) {
        ScheduleRepeat.Daily -> R.string.schedules_daily
        ScheduleRepeat.Weekdays -> R.string.schedules_weekdays
        ScheduleRepeat.Weekly -> R.string.schedules_weekly
        ScheduleRepeat.Monthly -> R.string.schedules_monthly
        ScheduleRepeat.Interval -> R.string.schedules_interval
        ScheduleRepeat.Once -> R.string.schedules_once
        ScheduleRepeat.Cron -> R.string.schedules_cron
        ScheduleRepeat.Custom -> R.string.schedules_custom
    }

@Composable
internal fun scheduleStatus(value: String) =
    stringResource(
        when (value) {
            "active" -> R.string.schedules_active
            "paused" -> R.string.schedules_paused
            else -> R.string.schedules_ended
        },
    )

@Composable
internal fun scheduleError(code: String): String =
    stringResource(
        when (code) {
            "title_required" -> R.string.schedules_error_title
            "prompt_required" -> R.string.schedules_error_prompt
            "session_required" -> R.string.schedules_error_session
            "interval_minimum",
            "interval_invalid" -> R.string.schedules_error_interval
            "choose_day" -> R.string.schedules_error_days
            "minute_precision" -> R.string.schedules_error_precision
            "time_gap" -> R.string.schedules_error_gap
            "future_time" -> R.string.schedules_error_future
            "date_time_zone" -> R.string.schedules_error_date
            "cron_fields",
            "cron_field",
            "cron_no_runs" -> R.string.schedules_error_cron
            "schedule_conflict" -> R.string.schedules_error_conflict
            else -> R.string.schedules_error_request
        },
    ) +
        if (
            code in
                setOf(
                    "title_required",
                    "prompt_required",
                    "session_required",
                    "interval_minimum",
                    "interval_invalid",
                    "choose_day",
                    "minute_precision",
                    "time_gap",
                    "future_time",
                    "date_time_zone",
                    "cron_fields",
                    "cron_field",
                    "cron_no_runs",
                )
        )
            ""
        else " ($code)"

@Composable
internal fun scheduleRule(task: ScheduleTask): String {
    val rule = remember(task) { task.timing }
    val name = stringResource(scheduleRepeatLabel(rule.repeat))
    return when (rule.repeat) {
        ScheduleRepeat.Interval ->
            "$name · ${rule.interval} " +
                stringResource(
                    when (rule.intervalUnit) {
                        ScheduleIntervalUnit.Seconds -> R.string.schedules_seconds
                        ScheduleIntervalUnit.Minutes -> R.string.schedules_minutes
                        ScheduleIntervalUnit.Hours -> R.string.schedules_hours
                    },
                )
        ScheduleRepeat.Cron -> "$name · ${rule.cron} · ${rule.zone}"
        ScheduleRepeat.Custom -> "$name · ${rule.customRule.orEmpty()}"
        ScheduleRepeat.Once -> "$name · ${rule.date} ${rule.time} · ${rule.zone}"
        ScheduleRepeat.Weekly ->
            "$name · ${rule.weekdays.sorted().joinToString { DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }} · ${rule.time} · ${rule.zone}"
        else -> "$name · ${rule.time} · ${rule.zone}"
    }
}

@Composable
internal fun scheduleDestination(task: ScheduleTask): String {
    val kind = task.target.kind
    val label =
        stringResource(
            when (kind) {
                ScheduleTargetKind.Session -> R.string.schedules_existing
                ScheduleTargetKind.NewOnce -> R.string.schedules_new_once
                else -> R.string.schedules_new_each
            },
        )
    return label +
        (if (kind == ScheduleTargetKind.Session) task.targetSessionId?.let { " · $it" }.orEmpty()
        else task.target.cwd?.let { " · $it" }.orEmpty())
}

/** Wide screens grow the task column to a board; phones keep the original reading column. */
@Composable
internal fun SchedulesPane(
    modifier: Modifier,
    wide: Boolean,
    content: @Composable (modifier: Modifier, padding: PaddingValues) -> Unit,
) {
    if (wide) {
        Box(
            modifier.padding(Spacing.Large),
            contentAlignment = Alignment.TopCenter,
        ) {
            content(
                Modifier.fillMaxHeight().widthIn(max = ContentWidths.Board).fillMaxWidth(),
                PaddingValues(bottom = 96.dp),
            )
        }
    } else {
        ReadablePane(modifier) { inset ->
            content(Modifier.fillMaxSize(), PaddingValues(inset, Spacing.Large, inset, 96.dp))
        }
    }
}

/** Native list row: title, rule as supporting text, status at the end, tap opens the detail. */
@Composable
internal fun SchedulesTaskRow(task: ScheduleTask, onAction: (SchedulesAction) -> Unit) {
    SettingsRow(
        title = task.title,
        supporting = scheduleRule(task),
        value = scheduleStatus(task.status),
        chevron = true,
        onClick = { onAction(SchedulesAction.OpenTask(task.id)) },
    )
}

/** Template seeds; each opens the existing editor and pre-fills only its timing. */
private data class ScheduleTemplate(
    val repeat: ScheduleRepeat,
    val label: Int,
    val icon: Int,
)

private val ScheduleTemplates =
    listOf(
        ScheduleTemplate(
            ScheduleRepeat.Daily,
            R.string.schedules_template_daily,
            R.drawable.ic_alarm,
        ),
        ScheduleTemplate(
            ScheduleRepeat.Weekdays,
            R.string.schedules_template_weekdays,
            R.drawable.ic_calendar,
        ),
        ScheduleTemplate(
            ScheduleRepeat.Weekly,
            R.string.schedules_template_weekly,
            R.drawable.ic_repeat,
        ),
        ScheduleTemplate(
            ScheduleRepeat.Once,
            R.string.schedules_template_once,
            R.drawable.ic_alarm,
        ),
    )

/** Same time of day for every template; the editor stays free to change it. */
private const val ScheduleTemplateTime = "09:00:00"

/** Reuses the existing create flow and timing actions; no new action or backend call. */
private fun createFromTemplate(template: ScheduleTemplate, onAction: (SchedulesAction) -> Unit) {
    onAction(SchedulesAction.Create)
    onAction(SchedulesAction.Repeat(template.repeat))
    onAction(SchedulesAction.Time(ScheduleTemplateTime))
}

/**
 * Empty state. The template seeds are cards rather than chips so they read as shortcuts that open a
 * pre-filled editor, not as labels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SchedulesEmptyPanel(
    state: SchedulesUiState,
    filtered: Boolean,
    onAction: (SchedulesAction) -> Unit,
) {
    val canCreate = state.connected && state.canManage && !state.busy
    if (filtered) {
        EmptyStatePanel(
            title = stringResource(R.string.schedules_empty),
            body = stringResource(R.string.schedules_empty_filtered_help),
            icon = R.drawable.ic_search,
        )
        return
    }
    EmptyStatePanel(
        title = stringResource(R.string.schedules_empty_title),
        body = stringResource(R.string.schedules_empty_body),
        // A plus here would compete with the create button; the bell reads as "scheduled".
        icon = R.drawable.ic_s_bell,
        primaryLabel = if (canCreate) stringResource(R.string.schedules_create) else null,
        onPrimary = if (canCreate) ({ onAction(SchedulesAction.Create) }) else null,
        extra = {
            Text(
                stringResource(R.string.schedules_empty_templates),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                ScheduleTemplates.forEach { template ->
                    ScheduleTemplateCard(template, canCreate) {
                        createFromTemplate(template, onAction)
                    }
                }
            }
        },
    )
}

/** One template shortcut: tonal card, role icon, and the seed's timing as its label. */
@Composable
private fun ScheduleTemplateCard(
    template: ScheduleTemplate,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.Medium, vertical = Spacing.Medium)
                .heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Icon(
                painterResource(template.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(stringResource(template.label), style = MaterialTheme.typography.labelLarge)
        }
    }
}

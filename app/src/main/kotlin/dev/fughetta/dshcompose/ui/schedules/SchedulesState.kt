package dev.fughetta.dshcompose.ui.schedules

import dev.fughetta.dshcompose.domain.schedules.*
import java.time.Instant
import kotlinx.collections.immutable.*

enum class SchedulesFilter(val active: Boolean?) {
    All(null),
    Active(true),
    Other(false),
}

data class SchedulesWorkspace(val id: String, val title: String, val path: String)

data class SchedulesSession(val sessionId: String, val title: String?)

data class SchedulesContext(
    val workspaces: PersistentList<SchedulesWorkspace> = persistentListOf(),
    val sessions: PersistentList<SchedulesSession> = persistentListOf(),
    val selectedWorkspaceId: String? = null,
    val sessionId: String? = null,
)

data class SchedulesEditorState(
    val original: ScheduleTask?,
    val title: String,
    val prompt: String,
    val timing: ScheduleDraft,
    val initialTiming: ScheduleDraft,
    val target: ScheduleTarget,
    val initialTarget: ScheduleTarget,
    val initialTitle: String,
    val initialPrompt: String,
    val preview: PersistentList<Instant> = persistentListOf(),
    val validation: String? = null,
    val discard: Boolean = false,
    val uncertain: Boolean = false,
) {
    val dirty
        get() =
            title != initialTitle ||
                prompt != initialPrompt ||
                timing != initialTiming ||
                target != initialTarget
}

data class SchedulesDetailState(
    val task: ScheduleTask,
    val runs: PersistentList<ScheduleRun> = persistentListOf(),
    val historyLoading: Boolean = false,
    val historyLoaded: Boolean = false,
    val nextBefore: String? = null,
    val pruned: Boolean = false,
    val unavailable: Boolean = false,
    val retentionDays: Long? = null,
    val retentionRecords: Long? = null,
    val historyError: String? = null,
)

data class SchedulesUiState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val canManage: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val busy: Boolean = false,
    val rows: PersistentList<ScheduleTask> = persistentListOf(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val filter: SchedulesFilter = SchedulesFilter.All,
    val query: String = "",
    val filtering: Boolean = false,
    val detail: SchedulesDetailState? = null,
    val editor: SchedulesEditorState? = null,
    val deleting: ScheduleTask? = null,
    val context: SchedulesContext = SchedulesContext(),
    val error: String? = null,
    val notice: String? = null,
    val openSessionId: String? = null,
)

sealed interface SchedulesAction {
    data class Observe(val value: Boolean) : SchedulesAction

    data object Refresh : SchedulesAction

    data object LoadMore : SchedulesAction

    data class Filter(val value: SchedulesFilter) : SchedulesAction

    data class Query(val value: String) : SchedulesAction

    data class OpenTask(val id: String) : SchedulesAction

    data object CloseTask : SchedulesAction

    data object LoadHistory : SchedulesAction

    data object RetryHistory : SchedulesAction

    data object Create : SchedulesAction

    data object Edit : SchedulesAction

    data class Title(val value: String) : SchedulesAction

    data class Prompt(val value: String) : SchedulesAction

    data class Repeat(val value: ScheduleRepeat) : SchedulesAction

    data class Time(val value: String) : SchedulesAction

    data class Date(val value: String) : SchedulesAction

    data class Zone(val value: String) : SchedulesAction

    data class Interval(val value: String) : SchedulesAction

    data class IntervalUnit(val value: ScheduleIntervalUnit) : SchedulesAction

    data class Weekday(val value: Int) : SchedulesAction

    data class MonthDay(val value: Int) : SchedulesAction

    data class Cron(val value: String) : SchedulesAction

    data class Target(val value: ScheduleTargetKind) : SchedulesAction

    data class Session(val id: String) : SchedulesAction

    data class Workspace(val id: String?) : SchedulesAction

    data class AgentPreset(val value: String) : SchedulesAction

    data object Save : SchedulesAction

    data object CloseEditor : SchedulesAction

    data object DiscardEditor : SchedulesAction

    data object KeepEditor : SchedulesAction

    data object Run : SchedulesAction

    data object ToggleActive : SchedulesAction

    data object AskDelete : SchedulesAction

    data object CancelDelete : SchedulesAction

    data object ConfirmDelete : SchedulesAction

    data class OpenSession(val id: String) : SchedulesAction
}

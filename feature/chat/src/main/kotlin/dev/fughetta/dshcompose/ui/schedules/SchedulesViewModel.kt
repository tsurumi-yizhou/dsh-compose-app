package dev.fughetta.dshcompose.ui.schedules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.schedules.*
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import java.time.Instant
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SchedulesViewModel(
    private val repository: StateFlow<SchedulesRepository?>,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutable = MutableStateFlow(SchedulesUiState())
    val uiState: StateFlow<SchedulesUiState> = mutable.asStateFlow()
    private val ui
        get() = mutable.value

    private var owner: SchedulesRepository? = null
    private var epoch = 0L
    private var observing = false
    private var dirty = true
    private var read: Job? = null
    private var history: Job? = null
    private var detailRead: Job? = null
    private var preview: Job? = null
    private var write: Job? = null
    private var offset = 0
    private var readNumber = 0L

    private fun update(value: SchedulesUiState) {
        mutable.value = value.copy(revision = ui.revision + 1)
    }

    private var changes: Job? = null

    init {
        viewModelScope.launch { repository.collect { connect(it) } }
    }

    fun context(value: SchedulesContext) {
        if (value != ui.context) update(ui.copy(context = value))
    }

    fun consumeOpenSession(): String? {
        val id = ui.openSessionId ?: return null
        update(ui.copy(openSessionId = null))
        return id.takeIf { owner?.active == true }
    }

    private fun current(client: SchedulesRepository, own: Long) =
        owner === client && epoch == own && client.active

    private fun connect(client: SchedulesRepository?) {
        if (owner === client) return
        closeJobs()
        changes?.cancel()
        epoch++
        owner = client
        dirty = true
        offset = 0
        update(
            SchedulesUiState(
                connected = client?.active == true,
                canManage = client?.canManage == true,
                context = ui.context,
            ),
        )
        if (client != null)
            changes = viewModelScope.launch { client.changes.collect { onChanged() } }
        if (observing) refresh()
    }

    private fun closeJobs() {
        readNumber++
        read?.cancel()
        history?.cancel()
        detailRead?.cancel()
        preview?.cancel()
        write?.cancel()
    }

    override fun onCleared() {
        closeJobs()
        changes?.cancel()
    }

    private fun onChanged() {
        dirty = true
        readNumber++
        read?.cancel()
        if (!observing) update(ui.copy(loading = false, loadingMore = false, filtering = false))
        if (observing && !ui.busy) {
            refresh()
            if (ui.detail != null) {
                history?.cancel()
                update(ui.copy(detail = ui.detail?.copy(historyLoading = false)))
                loadHistory(false)
            }
        }
    }

    fun dispatch(action: SchedulesAction) {
        when (action) {
            is SchedulesAction.Observe -> {
                observing = action.value
                if (observing) {
                    if (dirty) refresh()
                }
            }
            SchedulesAction.Refresh -> {
                owner?.invalidate()
                dirty = true
                refresh()
                refreshDetail()
            }
            SchedulesAction.LoadMore -> if (ui.hasMore && !ui.loadingMore && !ui.loading) load(true)
            is SchedulesAction.Filter -> {
                dirty = true
                update(ui.copy(filter = action.value))
                refresh()
            }
            is SchedulesAction.Query -> {
                dirty = true
                update(ui.copy(query = action.value))
                refresh()
            }
            is SchedulesAction.OpenTask ->
                ui.rows
                    .firstOrNull { it.id == action.id }
                    ?.let {
                        history?.cancel()
                        update(ui.copy(detail = SchedulesDetailState(it)))
                        loadHistory(false)
                    }
            SchedulesAction.CloseTask -> {
                history?.cancel()
                detailRead?.cancel()
                update(ui.copy(detail = null))
            }
            SchedulesAction.LoadHistory -> loadHistory(true)
            SchedulesAction.RetryHistory ->
                loadHistory(
                    ui.detail?.historyLoaded == true &&
                        ui.detail?.historyError != "delivery_cursor_not_found",
                )
            SchedulesAction.Create -> openEditor(null)
            SchedulesAction.Edit -> ui.detail?.task?.let(::openEditor)
            is SchedulesAction.Title -> editor { it.copy(title = action.value) }
            is SchedulesAction.Prompt -> editor { it.copy(prompt = action.value) }
            is SchedulesAction.Repeat -> timing { it.copy(repeat = action.value) }
            is SchedulesAction.Time -> timing { it.copy(time = action.value) }
            is SchedulesAction.Date -> timing { it.copy(date = action.value) }
            is SchedulesAction.Zone -> timing { it.copy(zone = action.value) }
            is SchedulesAction.Interval -> timing { it.copy(interval = action.value) }
            is SchedulesAction.IntervalUnit -> timing { it.copy(intervalUnit = action.value) }
            is SchedulesAction.Weekday ->
                timing { it.copy(weekdays = it.weekdays.toggle(action.value)) }
            is SchedulesAction.MonthDay ->
                timing { it.copy(monthDays = it.monthDays.toggle(action.value)) }
            is SchedulesAction.Cron -> timing { it.copy(cron = action.value) }
            is SchedulesAction.Target ->
                editor { it.copy(target = it.target.copy(kind = action.value)) }
            is SchedulesAction.Session ->
                editor { it.copy(target = it.target.copy(sessionId = action.id)) }
            is SchedulesAction.Workspace ->
                editor {
                    it.copy(
                        target =
                            it.target.copy(
                                cwd =
                                    ui.context.workspaces
                                        .firstOrNull { w -> w.id == action.id }
                                        ?.path,
                            ),
                    )
                }
            is SchedulesAction.AgentPreset ->
                editor { it.copy(target = it.target.copy(agentPreset = action.value)) }
            SchedulesAction.Save -> save()
            SchedulesAction.CloseEditor ->
                if (!ui.busy)
                    ui.editor?.let {
                        if (it.dirty) update(ui.copy(editor = it.copy(discard = true)))
                        else update(ui.copy(editor = null))
                    }
            SchedulesAction.DiscardEditor ->
                if (!ui.busy) {
                    preview?.cancel()
                    update(ui.copy(editor = null))
                }
            SchedulesAction.KeepEditor ->
                ui.editor?.let { update(ui.copy(editor = it.copy(discard = false))) }
            SchedulesAction.Run ->
                ui.detail?.task?.let { task ->
                    mutate {
                        it.run(task)
                        null
                    }
                }
            SchedulesAction.ToggleActive ->
                ui.detail?.task?.let { task ->
                    mutate { it.update(task, ScheduleChanges(active = !task.active)) }
                }
            SchedulesAction.AskDelete ->
                if (!ui.busy && ui.canManage) update(ui.copy(deleting = ui.detail?.task))
            SchedulesAction.CancelDelete -> if (!ui.busy) update(ui.copy(deleting = null))
            SchedulesAction.ConfirmDelete ->
                ui.deleting?.let { task ->
                    mutate { client ->
                        client.delete(task)
                        null
                    }
                }
            is SchedulesAction.OpenSession ->
                owner?.let {
                    if (ui.connected && !ui.busy) update(ui.copy(openSessionId = action.id))
                }
        }
    }

    private fun Set<Int>.toggle(value: Int) = if (value in this) this - value else this + value

    private fun refresh() {
        if (!ui.busy) load(false)
    }

    private fun load(more: Boolean) {
        val client = owner ?: return
        if (!ui.connected) return
        val own = epoch
        val number = ++readNumber
        val filter = ui.filter
        val query = ui.query.trim()
        read?.cancel()
        update(
            ui.copy(
                loading = !more,
                loadingMore = more,
                filtering = query.isNotEmpty(),
                error = null,
            ),
        )
        read = viewModelScope.launch {
            try {
                if (query.isNotEmpty()) {
                    delay(200)
                    val all = client.catalog()
                    val matches =
                        withContext(computation) {
                            all.filter {
                                    (filter.active == null || it.active == filter.active) &&
                                        (it.title.contains(query, true) ||
                                            it.prompt.contains(query, true) ||
                                            it.id.contains(query, true) ||
                                            it.involvedSessionIds.any { id ->
                                                id.contains(query, true)
                                            })
                                }
                                .toPersistentList()
                        }
                    if (current(client, own) && number == readNumber) {
                        update(
                            ui.copy(
                                rows = matches,
                                total = matches.size,
                                hasMore = false,
                                loading = false,
                                loadingMore = false,
                                filtering = false,
                            ),
                        )
                        dirty = false
                    }
                } else {
                    val page = client.list(filter.active, if (more) offset else 0)
                    if (current(client, own) && number == readNumber) {
                        offset = page.offset + page.shown
                        val rows =
                            if (more) (ui.rows + page.items).distinctBy { it.id }.toPersistentList()
                            else page.items.toPersistentList()
                        update(
                            ui.copy(
                                detail =
                                    ui.detail?.let { detail ->
                                        rows
                                            .firstOrNull { it.id == detail.task.id }
                                            ?.let { detail.copy(task = it) } ?: detail
                                    },
                                rows = rows,
                                total = page.total,
                                hasMore = offset < page.total && page.shown > 0,
                                loading = false,
                                loadingMore = false,
                                filtering = false,
                            ),
                        )
                        dirty = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own) && number == readNumber) {
                    update(
                        ui.copy(
                            loading = false,
                            loadingMore = false,
                            filtering = false,
                            error = code(error),
                        ),
                    )
                }
            }
        }
    }

    private fun refreshDetail() {
        val selected = ui.detail?.task?.id ?: return
        val client = owner ?: return
        if (!ui.connected || ui.busy) return
        val own = epoch
        detailRead?.cancel()
        detailRead = viewModelScope.launch {
            try {
                val all = client.catalog()
                if (current(client, own) && ui.detail?.task?.id == selected) {
                    val task = all.firstOrNull { it.id == selected }
                    history?.cancel()
                    update(
                        ui.copy(
                            detail = task?.let { SchedulesDetailState(it) },
                            error = if (task == null) "schedule_not_found" else null,
                        ),
                    )
                    if (task != null) loadHistory(false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own) && ui.detail?.task?.id == selected)
                    update(ui.copy(error = code(error)))
            }
        }
    }

    private fun loadHistory(more: Boolean) {
        val detail = ui.detail ?: return
        val client = owner ?: return
        if (detail.historyLoading || more && detail.nextBefore == null) return
        val own = epoch
        history?.cancel()
        update(ui.copy(detail = detail.copy(historyLoading = true, historyError = null)))
        history = viewModelScope.launch {
            try {
                val page = client.history(detail.task.id, if (more) detail.nextBefore else null)
                val present = ui.detail
                if (current(client, own) && present?.task?.id == detail.task.id)
                    update(
                        ui.copy(
                            detail =
                                present.copy(
                                    runs =
                                        (if (more) present.runs + page.runs else page.runs)
                                            .toPersistentList(),
                                    historyLoading = false,
                                    historyLoaded = true,
                                    nextBefore = page.nextBefore,
                                    pruned = page.pruned,
                                    unavailable = page.unavailable,
                                    retentionDays = page.retentionDays,
                                    retentionRecords = page.retentionRecords,
                                ),
                        ),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own) && ui.detail?.task?.id == detail.task.id)
                    update(
                        ui.copy(
                            detail =
                                ui.detail?.copy(historyLoading = false, historyError = code(error)),
                        ),
                    )
            }
        }
    }

    private fun openEditor(task: ScheduleTask?) {
        if (!ui.connected || !ui.canManage || ui.busy || ui.editor != null) return
        val ctx = ui.context
        val draft = task?.timing ?: ScheduleDraft()
        val target =
            task?.target
                ?: ScheduleTarget(
                    kind =
                        if (ctx.sessionId != null) ScheduleTargetKind.Session
                        else ScheduleTargetKind.NewOnce,
                    sessionId = ctx.sessionId,
                    cwd = ctx.workspaces.firstOrNull { it.id == ctx.selectedWorkspaceId }?.path,
                )
        update(
            ui.copy(
                context = ctx,
                editor =
                    SchedulesEditorState(
                        task,
                        task?.title.orEmpty(),
                        task?.prompt.orEmpty(),
                        draft,
                        draft,
                        target,
                        target,
                        task?.title.orEmpty(),
                        task?.prompt.orEmpty(),
                    ),
                error = null,
            ),
        )
        validate()
    }

    private fun editor(change: (SchedulesEditorState) -> SchedulesEditorState) {
        val value = ui.editor?.takeIf { !ui.busy && !it.uncertain } ?: return
        update(ui.copy(editor = change(value), error = null))
        validate()
    }

    private fun timing(change: (ScheduleDraft) -> ScheduleDraft) = editor {
        it.copy(timing = change(it.timing))
    }

    private fun SchedulesEditorState.input() = ScheduleInput(title, prompt, timing, target)

    private fun validation(value: SchedulesEditorState): String? =
        value.input().validate(Instant.now(), value.original)

    private fun validate() {
        val value = ui.editor ?: return
        preview?.cancel()
        preview = viewModelScope.launch {
            val result =
                withContext(computation) {
                    val error = validation(value)
                    error to
                        if (error == null)
                            runCatching {
                                    value.timing
                                        .preview(
                                            Instant.now(),
                                            anchor =
                                                if (value.timing == value.initialTiming)
                                                    value.original?.scheduledAt
                                                else null,
                                        )
                                        .toPersistentList()
                                }
                                .getOrDefault(persistentListOf())
                        else persistentListOf()
                }
            if (ui.editor === value)
                update(
                    ui.copy(
                        editor = value.copy(validation = result.first, preview = result.second),
                    ),
                )
        }
    }

    private fun save() {
        if (ui.busy) return
        val value = ui.editor?.takeIf { !it.uncertain } ?: return
        val client = owner ?: return
        val own = epoch
        write?.cancel()
        write = viewModelScope.launch {
            val error = withContext(computation) { validation(value) }
            if (
                !current(client, own) ||
                    ui.editor?.input() != value.input() ||
                    ui.editor?.original != value.original
            )
                return@launch
            if (error != null) {
                update(ui.copy(editor = ui.editor?.copy(validation = error)))
                return@launch
            }
            if (value.original != null && !value.dirty) {
                update(ui.copy(editor = null))
                return@launch
            }
            mutate(editorSave = true) {
                withContext(computation) {
                    SaveScheduleUseCase(it, now = java.time.Instant::now)(
                        value.input(),
                        value.original,
                    )
                }
            }
        }
    }

    private fun mutate(
        editorSave: Boolean = false,
        block: suspend (SchedulesRepository) -> ScheduleTask?,
    ) {
        val client = owner ?: return
        if (!ui.connected || !ui.canManage || ui.busy) return
        val own = epoch
        val deleting = ui.deleting?.id
        readNumber++
        read?.cancel()
        update(
            ui.copy(busy = true, loading = false, loadingMore = false, error = null, notice = null),
        )
        write = viewModelScope.launch {
            try {
                val changed = block(client)
                if (current(client, own)) {
                    if (deleting != null) {
                        history?.cancel()
                        update(ui.copy(detail = null, deleting = null))
                    } else if (changed != null)
                        update(
                            ui.copy(
                                detail =
                                    ui.detail
                                        ?.takeIf { it.task.id == changed.id }
                                        ?.copy(task = changed),
                            ),
                        )
                    if (editorSave) update(ui.copy(editor = null))
                    dirty = true
                    update(ui.copy(notice = "saved"))
                }
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                if (current(client, own))
                    update(
                        ui.copy(
                            error = "request_failed",
                            editor =
                                ui.editor?.let {
                                    if (editorSave) it.copy(uncertain = true) else it
                                },
                        ),
                    )
            } catch (error: Exception) {
                if (current(client, own))
                    update(
                        ui.copy(
                            error = code(error),
                            editor =
                                ui.editor?.let {
                                    if (
                                        editorSave &&
                                            (error as? ScheduleOperationException)?.rejected != true
                                    )
                                        it.copy(uncertain = true)
                                    else it
                                },
                        ),
                    )
            } finally {
                if (current(client, own)) {
                    update(ui.copy(busy = false))
                    if (dirty && observing) refresh()
                }
            }
        }
    }

    private fun code(error: Exception): String =
        if (error is ScheduleOperationException) error.code else "request_failed"
}

package dev.fughetta.dshcompose.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.jobs.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Streams belong to one visible dialog and one client; no history is retained after dismissing it.
 */
class JobsViewModel(
    private val repository: StateFlow<JobsRepository?>,
    private val files: JobsRepository,
) : ViewModel() {
    private val mutable = MutableStateFlow<JobsUiState?>(null)
    val uiState = mutable.asStateFlow()
    private var state: JobsUiState?
        get() = mutable.value
        set(value) {
            mutable.value = value
        }

    private var client: JobsRepository? = null

    init {
        viewModelScope.launch { repository.collect(::connect) }
    }

    private var epoch = 0L
    private var detailEpoch = 0L
    private var observing = false
    private var roster: Job? = null
    private var follow: Job? = null
    private var mutation: Job? = null
    private var share: Job? = null
    private var exportGeneration = 0L
    private var exportRequest = 0L
    private var lastShare = 0L

    private data class SaveSelection(
        val request: JobSaveRequest,
        val lines: List<String>,
        val generation: Long,
        var launched: Boolean = false,
    )

    private var pendingSave: SaveSelection? = null

    private fun connect(value: JobsRepository?) {
        if (client === value) return
        client = value
        closeDialog()
    }

    fun open(id: String) {
        closeDialog()
        state = JobsUiState(id, canStop = client?.canStop == true)
    }

    fun closeDialog() {
        resetExport()
        epoch++
        detailEpoch++
        observing = false
        roster?.cancel()
        follow?.cancel()
        mutation?.cancel()
        state = null
    }

    fun observe(value: Boolean) {
        if (observing == value) return
        observing = value
        if (value) refresh()
        else {
            epoch++
            detailEpoch++
            roster?.cancel()
            follow?.cancel()
        }
    }

    fun refresh() {
        val view = state ?: return
        val owner =
            client ?: return update { it.copy(loading = false, error = "jobs/not-connected") }
        if (!observing) return
        val own = ++epoch
        roster?.cancel()
        update { it.copy(loading = true, error = null) }
        roster = viewModelScope.launch {
            try {
                owner.watch(view.sessionId).conflate().collect { frame ->
                    if (!current(owner, own)) return@collect
                    update {
                        it.copy(
                            jobs = frame.jobs.toPersistentList(),
                            loading = frame.loading,
                            error = null,
                        )
                    }
                }
                if (current(owner, own)) update { it.copy(loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own)) update { it.copy(loading = false, error = code(error)) }
            }
        }
        view.output?.job?.let { startOutput(it, resetFiles = false) }
    }

    fun openOutput(id: String) {
        state?.jobs?.firstOrNull { it.id == id }?.let { startOutput(it) }
    }

    private fun startOutput(job: JobInfo, resetFiles: Boolean = true) {
        if (resetFiles) resetExport()
        val owner = client ?: return
        val view = state ?: return
        val own = epoch
        val detail = ++detailEpoch
        follow?.cancel()
        update { it.copy(output = JobOutputUiState(JobOutput(job))) }
        follow = viewModelScope.launch {
            try {
                owner.output(view.sessionId, job).collect { output ->
                    if (current(owner, own) && detail == detailEpoch)
                        update { current ->
                            val previous = current.output
                            current.copy(
                                output =
                                    JobOutputUiState(output)
                                        .copy(
                                            stopping =
                                                previous?.stopping == true && output.job.live,
                                            stopRequested =
                                                previous?.stopRequested == true && output.job.live,
                                            confirmStop =
                                                previous?.confirmStop == true && output.job.live,
                                            notice = previous?.notice,
                                            error = previous?.error,
                                        ),
                            )
                        }
                    delay(32)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own) && detail == detailEpoch)
                    detail { it.copy(data = it.data.copy(loading = false), error = code(error)) }
            }
        }
    }

    fun closeOutput() {
        resetExport()
        detailEpoch++
        follow?.cancel()
        update { it.copy(output = null) }
    }

    fun requestStop() {
        if (
            state?.canStop == true &&
                state?.output?.let {
                    !it.loading && it.job.live && !it.stopping && !it.stopRequested
                } == true
        )
            detail { it.copy(confirmStop = true) }
    }

    fun cancelStop() {
        detail { it.copy(confirmStop = false) }
    }

    fun confirmStop() {
        val owner = client ?: return
        val view = state ?: return
        val output = view.output?.takeIf { it.confirmStop && it.job.live && !it.stopping } ?: return
        if (!view.canStop || mutation?.isActive == true) return
        val own = epoch
        val detail = detailEpoch
        detail { it.copy(stopping = true, confirmStop = false, error = null) }
        mutation = viewModelScope.launch {
            try {
                val requested = owner.stop(view.sessionId, output.job.id)
                if (current(owner, own) && detail == detailEpoch)
                    detail {
                        it.copy(
                            stopping = false,
                            stopRequested = requested && it.job.live,
                            notice =
                                if (requested) R.string.jobs_stop_requested
                                else R.string.jobs_already_ended,
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own) && detail == detailEpoch)
                    detail { it.copy(stopping = false, error = code(error)) }
            }
        }
    }

    private fun resetExport() {
        exportGeneration++
        lastShare = 0
        share?.cancel()
        if (pendingSave?.launched != true) pendingSave = null
        update { it.copy(export = JobExportState()) }
    }

    fun shareOutput() {
        val view = state ?: return
        val lines = view.output?.lines?.takeIf { it.isNotEmpty() } ?: return
        if (view.export.sharing || view.export.saving) return
        val generation = exportGeneration
        val request = ++exportRequest
        lastShare = request
        update { it.copy(export = it.export.copy(sharing = true, shareFailed = false)) }
        share = viewModelScope.launch {
            try {
                val file = java.io.File(files.share(lines))
                if (generation == exportGeneration)
                    update {
                        it.copy(
                            export = it.export.copy(shareRequest = JobShareRequest(request, file)),
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == exportGeneration) shareFailed(request)
            }
        }
    }

    fun consumeShare(): JobShareRequest? {
        val request = state?.export?.shareRequest ?: return null
        update { it.copy(export = it.export.copy(sharing = false, shareRequest = null)) }
        return request
    }

    fun shareFailed(request: Long) {
        if (request == lastShare)
            update {
                it.copy(
                    export =
                        it.export.copy(sharing = false, shareFailed = true, shareRequest = null),
                )
            }
    }

    fun requestSave() {
        val view = state ?: return
        val lines = view.output?.lines?.takeIf { it.isNotEmpty() } ?: return
        if (view.export.saving || view.export.sharing || pendingSave != null) return
        val request = JobSaveRequest(++exportRequest)
        pendingSave = SaveSelection(request, lines, exportGeneration)
        update {
            it.copy(
                export = it.export.copy(saving = true, saveFailed = false, saveRequest = request),
            )
        }
    }

    fun savePickerOpened(id: Long): Boolean {
        val selected = pendingSave?.takeIf { it.request.id == id && !it.launched } ?: return false
        selected.launched = true
        update { it.copy(export = it.export.copy(saveRequest = null)) }
        return true
    }

    fun saveDocument(destination: String?, failed: Boolean = false) {
        val selected = pendingSave ?: return
        pendingSave = null
        if (destination == null) {
            if (selected.generation == exportGeneration)
                update {
                    it.copy(
                        export =
                            it.export.copy(saving = false, saveFailed = failed, saveRequest = null),
                    )
                }
            return
        }
        viewModelScope.launch {
            var failed = false
            try {
                files.save(destination, selected.lines)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                if (selected.generation == exportGeneration)
                    update {
                        it.copy(
                            export =
                                it.export.copy(
                                    saving = false,
                                    saveFailed = failed,
                                    saveRequest = null,
                                ),
                        )
                    }
            }
        }
    }

    fun handle(action: AppAction): Boolean {
        when (action) {
            is AppAction.OpenJobs -> open(action.sessionId)
            AppAction.CloseJobs -> closeDialog()
            is AppAction.ObserveJobs -> observe(action.observing)
            AppAction.RefreshJobs -> refresh()
            AppAction.ShareJobOutput -> shareOutput()
            AppAction.SaveJobOutput -> requestSave()
            is AppAction.OpenJobOutput -> openOutput(action.id)
            AppAction.CloseJobOutput -> closeOutput()
            AppAction.RequestJobStop -> requestStop()
            AppAction.CancelJobStop -> cancelStop()
            AppAction.ConfirmJobStop -> confirmStop()
            else -> return false
        }
        return true
    }

    private fun current(owner: JobsRepository, own: Long) =
        client === owner && epoch == own && state != null && owner.active

    private fun update(transform: (JobsUiState) -> JobsUiState) {
        state = state?.let(transform)
    }

    private fun detail(transform: (JobOutputUiState) -> JobOutputUiState) = update {
        it.copy(output = it.output?.let(transform))
    }

    private fun code(error: Exception) =
        (error as? JobOperationException)?.code ?: "jobs/read-failed"

    override fun onCleared() {
        closeDialog()
        client = null
    }
}

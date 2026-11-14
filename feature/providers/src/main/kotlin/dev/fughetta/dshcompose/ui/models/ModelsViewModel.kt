package dev.fughetta.dshcompose.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ModelDefaultsEditorState(
    val original: ModelDefaults,
    val catalog: ModelCatalog,
    val candidate: ModelSelection?,
    val stale: Boolean = false,
    val error: String? = null,
)

data class ModelDefaultsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val snapshot: ModelDefaults? = null,
    val catalog: ModelCatalog? = null,
    val editor: ModelDefaultsEditorState? = null,
    val error: String? = null,
    val notice: String? = null,
)

/** New-chat settings are independent from the currently open session's selection. */
class ModelsViewModel(
    private val repository: StateFlow<SessionModelsRepository?>,
    parent: CoroutineScope? = null,
    private val onSelected:
        suspend (String, SessionModelsRepository, ModelSelectionResult) -> Unit =
        { _, _, _ ->
        },
    private val onDraftSelected: (ModelSelection) -> Unit = {},
) : ViewModel() {
    private val scope = parent ?: viewModelScope

    private val mutable = MutableStateFlow(ModelDefaultsUiState())
    val uiState = mutable.asStateFlow()
    private val state
        get() = mutable.value

    private val updateDefaults = UpdateModelDefaultsUseCase()
    private var owner: SessionModelsRepository? = null
    private var connection = 0L
    private var observing = false
    private var read: Job? = null
    private var readNumber = 0L
    private var write: Job? = null
    private var refreshAfterWrite = false

    private val defaultsObserver = scope.launch {
        repository.collectLatest { operations ->
            connect(operations)
            operations?.changes?.drop(1)?.collect { invalidate() }
        }
    }

    fun handle(action: AppAction): Boolean {
        connect(repository.value)
        when (action) {
            is AppAction.ObserveModelDefaults -> observe(action.observing)
            AppAction.RefreshModelDefaults -> reload()
            AppAction.OpenDefaultModelPicker -> open()
            is AppAction.ChooseDefaultModel -> choose(action.selection)
            AppAction.ConfirmDefaultModel -> confirm()
            AppAction.CloseDefaultModelPicker -> dismiss()
            is AppAction.ChangeDefaultModelMode -> mode(action.remember)
            else -> return false
        }
        return true
    }

    private fun connect(client: SessionModelsRepository?) {
        if (owner === client) return
        connection++
        readNumber++
        read?.cancel()
        write?.cancel()
        refreshAfterWrite = false
        owner = client
        update(ModelDefaultsUiState(connected = client != null))
        if (observing) refresh()
    }

    fun observe(value: Boolean) {
        observing = value
        if (value) refresh()
        else {
            readNumber++
            read?.cancel()
            update(state.copy(loading = false))
        }
    }

    fun invalidate() {
        if (state.busy) {
            refreshAfterWrite = true
            return
        }
        update(
            state.copy(
                editor =
                    state.editor?.copy(
                        stale = true,
                        error = "models/default-stale",
                    ),
            ),
        )
        if (observing) refresh()
    }

    fun reload() {
        refresh(reopen = state.editor != null)
    }

    fun refresh(reopen: Boolean = false, preserveOutcome: Boolean = false) {
        if (state.busy) {
            refreshAfterWrite = true
            return
        }
        val client = owner ?: return
        val own = connection
        if (!current(client, own)) return
        val number = ++readNumber
        val previous = state.editor?.candidate
        read?.cancel()
        update(
            state.copy(
                loading = true,
                error = state.error.takeIf { preserveOutcome },
                notice = state.notice.takeIf { preserveOutcome },
                editor =
                    if (reopen) null
                    else
                        state.editor?.copy(
                            stale = true,
                            error = "models/default-stale",
                        ),
            ),
        )
        read = scope.launch {
            try {
                val (snapshot, catalog) =
                    coroutineScope {
                        val settings = async { client.defaults() }
                        val models = async { client.catalog() }
                        settings.await() to models.await()
                    }
                if (current(client, own) && number == readNumber) {
                    update(
                        state.copy(
                            snapshot = snapshot,
                            catalog = catalog,
                            loading = false,
                            editor =
                                if (reopen && snapshot?.writable == true)
                                    ModelDefaultsEditorState(
                                        snapshot,
                                        catalog,
                                        previous?.takeIf(catalog::accepts) ?: snapshot.selection,
                                    )
                                else state.editor,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && number == readNumber)
                    update(
                        state.copy(
                            loading = false,
                            error = "models/default-read-failed",
                        ),
                    )
            }
        }
    }

    fun open() {
        val snapshot = state.snapshot?.takeIf { it.writable } ?: return
        val catalog = state.catalog ?: return
        if (state.loading || state.busy || !state.connected || state.error != null) return
        update(
            state.copy(
                editor = ModelDefaultsEditorState(snapshot, catalog, snapshot.selection),
                notice = null,
            ),
        )
    }

    fun choose(selection: ModelSelection) {
        val editor = state.editor?.takeIf { !it.stale && !state.busy } ?: return
        if (editor.catalog.accepts(selection))
            update(state.copy(editor = editor.copy(candidate = selection, error = null)))
    }

    fun dismiss() {
        if (!state.busy) update(state.copy(editor = null))
    }

    fun confirm() {
        val editor = state.editor?.takeIf { !it.stale } ?: return
        val selection = editor.candidate ?: return
        save(editor.original, editor.catalog, selection, editor.original.rememberLastSelection)
    }

    fun mode(remember: Boolean) {
        if (state.editor != null) return
        val snapshot = state.snapshot ?: return
        val catalog = state.catalog ?: return
        val selection = snapshot.selection ?: return
        if (remember == snapshot.rememberLastSelection) return
        save(snapshot, catalog, selection, remember)
    }

    private fun save(
        original: ModelDefaults,
        catalog: ModelCatalog,
        selection: ModelSelection,
        remember: Boolean,
    ) {
        val client = owner ?: return
        val own = connection
        if (
            !current(client, own) ||
                state.busy ||
                state.loading ||
                state.editor == null && state.error != null
        )
            return
        readNumber++
        read?.cancel()
        update(
            state.copy(
                busy = true,
                error = null,
                notice = null,
                editor = state.editor?.copy(error = null),
            ),
        )
        write = scope.launch {
            try {
                val result = updateDefaults(client, original, catalog, selection, remember)
                if (!current(client, own)) return@launch
                update(
                    state.copy(
                        snapshot = result.snapshot,
                        editor = null,
                        error = if (result.confirmed) null else "models/default-unconfirmed",
                        notice = if (result.confirmed) "models/default-saved" else null,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) {
                    val code = (error as? ModelOperationException)?.code
                    val conflict = code == "settings/conflict"
                    val text =
                        when {
                            conflict -> "models/default-stale"
                            code == "models/default-inherited-effort" -> code
                            else -> "models/default-write-failed"
                        }
                    update(
                        state.copy(
                            error = text,
                            editor = state.editor?.copy(stale = conflict, error = text),
                        ),
                    )
                }
            } finally {
                if (current(client, own)) {
                    update(state.copy(busy = false))
                    if (refreshAfterWrite) {
                        refreshAfterWrite = false
                        if (observing) refresh(preserveOutcome = true)
                    }
                }
            }
        }
    }

    private fun current(client: SessionModelsRepository, own: Long) =
        owner === client && own == connection && repository.value === client

    private fun update(value: ModelDefaultsUiState) {
        mutable.value = value
    }

    private val pickerMutable = MutableStateFlow<ModelPickerState?>(null)
    val pickerUiState = pickerMutable.asStateFlow()
    private var pickerOwner: SessionModelsRepository? = repository.value
    private var pickerRequest = 0L
    private var pickerWork: Job? = null
    private val pickerObserver = scope.launch {
        repository.collectLatest { operations ->
            connectPicker(operations)
            operations?.changes?.drop(1)?.collect {
                pickerMutable.value
                    ?.takeIf { !it.busy }
                    ?.let { openPicker(it.sessionId, it.candidate) }
            }
        }
    }

    private fun connectPicker(owner: SessionModelsRepository?) {
        if (pickerOwner === owner) return
        pickerRequest++
        pickerWork?.cancel()
        pickerOwner = owner
        pickerMutable.value = null
    }

    fun openPicker(sessionId: String, selection: ModelSelection?) {
        connectPicker(repository.value)
        if (pickerMutable.value?.busy == true) return
        pickerWork?.cancel()
        val own = ++pickerRequest
        val owner = pickerOwner
        pickerMutable.value = ModelPickerState(sessionId, candidate = selection, loading = true)
        if (owner == null) {
            pickerMutable.value =
                pickerMutable.value!!.copy(loading = false, error = "models/offline")
            return
        }
        pickerWork = scope.launch {
            try {
                val catalog = owner.catalog()
                if (currentPicker(owner, own))
                    pickerMutable.value =
                        pickerMutable.value!!.copy(
                            catalog = catalog,
                            candidate = pickerMutable.value?.candidate ?: catalog.defaultSelection,
                            loading = false,
                        )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentPicker(owner, own))
                    pickerMutable.value =
                        pickerMutable.value!!.copy(loading = false, error = "models/load-failed")
            }
        }
    }

    fun choosePicker(selection: ModelSelection) {
        connectPicker(repository.value)
        val dialog = pickerMutable.value?.takeIf { !it.busy && !it.loading } ?: return
        if (dialog.catalog?.accepts(selection) != true) return
        pickerMutable.value = dialog.copy(candidate = selection, error = null)
    }

    fun confirmPicker() {
        connectPicker(repository.value)
        val dialog = pickerMutable.value?.takeIf { !it.busy && !it.loading } ?: return
        val selected = dialog.candidate ?: return
        if (dialog.catalog?.accepts(selected) != true) return
        if (dialog.sessionId == DRAFT_SESSION_ID) {
            onDraftSelected(selected)
            dismissPicker()
            return
        }
        val owner = pickerOwner ?: return
        val own = pickerRequest
        if (!currentPicker(owner, own)) return
        pickerMutable.value = dialog.copy(busy = true, error = null)
        pickerWork = scope.launch {
            try {
                val result = owner.select(dialog.sessionId, selected)
                if (!currentPicker(owner, own)) return@launch
                onSelected(dialog.sessionId, owner, result)
                if (currentPicker(owner, own)) pickerMutable.value = null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentPicker(owner, own))
                    pickerMutable.value =
                        pickerMutable.value!!.copy(busy = false, error = "models/select-failed")
            }
        }
    }

    fun invalidatePicker() {
        connectPicker(repository.value)
        pickerOwner?.invalidate()
    }

    fun dismissPicker() {
        if (pickerMutable.value?.busy == true) return
        pickerRequest++
        pickerWork?.cancel()
        pickerMutable.value = null
    }

    private fun currentPicker(owner: SessionModelsRepository, own: Long) =
        pickerOwner === owner &&
            repository.value === owner &&
            pickerRequest == own &&
            pickerMutable.value != null

    private fun closePicker() {
        pickerRequest++
        pickerObserver.cancel()
        pickerWork?.cancel()
        pickerOwner = null
        pickerMutable.value = null
    }

    override fun onCleared() {
        defaultsObserver.cancel()
        closePicker()
        read?.cancel()
        write?.cancel()
    }
}

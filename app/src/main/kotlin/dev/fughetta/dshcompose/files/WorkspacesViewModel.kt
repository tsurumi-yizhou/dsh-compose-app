package dev.fughetta.dshcompose.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.workspaces.*
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.settings.WorkspacePreferencesStore
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

class WorkspacesViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val publish: (WorkspacesUiState) -> Unit,
    private val preferences: WorkspacePreferencesStore,
    private val onRestored: (String) -> Unit = {},
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var owner: WorkspacesRepository? = null
    private var generation: Any? = null
    private var connection = 0L
    private var state = WorkspacesUiState()
    private var watch: Job? = null
    private var write: Job? = null
    private var host = ""
    private var preferencesWatch: Job? = null
    private var selectionEdited = false
    private var selectionRestored = false

    fun connect(client: WorkspacesRepository?, hostId: String) {
        val next = client?.generation
        if (owner === client && generation == next && host == hostId) return
        val sameHost = host == hostId
        val previous = state
        connection++
        watch?.cancel()
        write?.cancel()
        owner = client
        generation = next
        // A reconnect to the same host keeps the last read on screen until the new one lands, so
        // the conversation list does not blank and re-project on every link drop.
        val kept = sameHost && previous.loaded
        update(
            WorkspacesUiState(
                connected = client != null && next != null,
                loaded = kept,
                rows = if (kept) previous.rows else persistentListOf(),
                archived = if (kept) previous.archived else persistentSetOf(),
                pinned = if (kept) previous.pinned else persistentListOf(),
                selectedId = previous.selectedId.takeIf { sameHost },
                preferences = if (sameHost) previous.preferences else WorkspacePreferences(),
                preferencesLoaded = sameHost && previous.preferencesLoaded,
            ),
        )
        if (!sameHost) {
            host = hostId
            selectionEdited = false
            selectionRestored = false
            preferencesWatch?.cancel()
            watchPreferences(hostId)
        }
        if (state.connected) reload()
    }

    private fun watchPreferences(hostId: String) {
        preferencesWatch = scope.launch {
            try {
                preferences.watch(hostId).collect { prefs ->
                    if (host != hostId) return@collect
                    val restored =
                        if (selectionRestored || selectionEdited) state.selectedId
                        else prefs.selected
                    selectionRestored = true
                    update(
                        state.copy(
                            preferences = prefs,
                            preferencesLoaded = true,
                            preferencesError = null,
                            selectedId = restored,
                        ),
                    )
                    if (state.connected && !state.loading) emit()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (host == hostId)
                    update(
                        state.copy(
                            preferencesLoaded = true,
                            preferencesError = message(UiR.string.workspace_preferences_failed),
                        ),
                    )
            }
        }
    }

    fun reload() {
        if (state.writing) return
        if (preferencesWatch?.isActive != true) watchPreferences(host)
        val client = owner ?: return
        val own = connection
        watch?.cancel()
        update(state.copy(loading = true, error = null))
        watch = scope.launch {
            try {
                client.events().collect { event ->
                    if (!current(client, own)) return@collect
                    if (event is WorkspaceEvent.Reset) update(state.copy(loading = true))
                    else emit(loading = false)
                }
                if (current(client, own))
                    update(
                        state.copy(
                            loading = false,
                            error = message(UiR.string.workspaces_read_failed),
                        ),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own))
                    update(
                        state.copy(
                            loading = false,
                            error = message(UiR.string.workspaces_read_failed),
                        ),
                    )
            }
        }
    }

    suspend fun register(client: WorkspacesRepository, path: String): Workspace {
        val own = connection
        if (!current(client, own) || state.loading || state.writing || state.error != null)
            throw OperationFailure("request/stale")
        val repository = client
        val result = repository.create(path)
        if (current(client, own) && owner === repository) {
            emit()
            val parent = result.path.substringBeforeLast('/', "").ifEmpty { "/" }
            savePreferences { it.remembered(parent) }
        }
        return result
    }

    fun select(id: String?) {
        if (state.loading || id != null && state.rows.none { it.id == id }) return
        if (!state.preferencesLoaded) return
        selectionEdited = true
        update(state.copy(selectedId = id, showArchived = false))
        savePreferences { it.copy(selected = id) }
    }

    fun archived(show: Boolean) {
        update(state.copy(showArchived = show))
    }

    fun manage(id: String, operation: WorkspaceOperation) {
        if (state.loading || state.writing || !state.connected || state.error != null) return
        val row = state.rows.firstOrNull { it.id == id } ?: return
        update(
            state.copy(
                dialog =
                    WorkspaceDialogState(
                        id,
                        operation,
                        if (operation == WorkspaceOperation.Group)
                            state.preferences.groups[id].orEmpty()
                        else row.title,
                    ),
            ),
        )
    }

    fun title(value: String) {
        state.dialog
            ?.takeIf { !it.busy }
            ?.let { update(state.copy(dialog = it.copy(title = value, error = null))) }
    }

    fun dismiss() {
        if (state.dialog?.busy != true) update(state.copy(dialog = null))
    }

    fun confirm() {
        val dialog = state.dialog?.takeIf { !it.busy } ?: return
        if (dialog.operation == WorkspaceOperation.Rename && dialog.title.isBlank()) return
        if (dialog.operation == WorkspaceOperation.Group) {
            savePreferences(closeDialog = true) { it.grouped(dialog.id, dialog.title) }
            return
        }
        val client = owner ?: return
        val own = connection
        if (!current(client, own) || state.loading || state.writing || state.error != null) return
        val repository = client
        update(state.copy(writing = true, dialog = dialog.copy(busy = true, error = null)))
        write = scope.launch {
            try {
                when (dialog.operation) {
                    WorkspaceOperation.Rename -> {
                        repository.rename(dialog.id, dialog.title.trim())
                        if (!current(client, own) || repository !== owner) return@launch
                    }
                    WorkspaceOperation.Remove -> {
                        repository.delete(dialog.id)
                        if (!current(client, own) || repository !== owner) return@launch
                        savePreferences { it.forgotten(dialog.id) }
                    }
                    WorkspaceOperation.Group -> Unit
                }
                update(state.copy(writing = false, dialog = null))
                emit()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own))
                    update(
                        state.copy(
                            writing = false,
                            dialog =
                                dialog.copy(
                                    error =
                                        message(
                                            if (
                                                operationErrorCode(error) ==
                                                    "workspace/name-conflict"
                                            )
                                                UiR.string.workspace_name_conflict
                                            else UiR.string.workspace_write_failed,
                                        ),
                                ),
                        ),
                    )
            }
        }
    }

    fun move(id: String, beforeId: String?) {
        if (
            state.rows.none { it.id == id } ||
                id == beforeId ||
                beforeId != null && state.rows.none { it.id == beforeId }
        )
            return
        changeCollection { client -> WorkspaceEvent.Order(client.move(id, beforeId)) }
    }

    fun pinLocally(id: String, pinned: Boolean) {
        if (state.rows.none { it.id == id } || !state.preferencesLoaded) return
        savePreferences {
            it.copy(pinned = if (pinned) it.pinned.adding(id) else it.pinned.removing(id))
        }
    }

    fun rememberDirectory(path: String) {
        savePreferences { it.remembered(path) }
    }

    private fun savePreferences(
        closeDialog: Boolean = false,
        transform: (WorkspacePreferences) -> WorkspacePreferences,
    ) {
        val hostId = host
        val dialog = if (closeDialog) state.dialog?.copy(busy = true, error = null) else null
        if (dialog != null) update(state.copy(dialog = dialog))
        scope.launch {
            try {
                preferences.update(hostId, transform)
                if (host == hostId && dialog != null && state.dialog === dialog)
                    update(state.copy(dialog = null))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (host == hostId)
                    update(
                        state.copy(
                            preferencesError = message(UiR.string.workspace_preferences_failed),
                            dialog =
                                if (dialog != null && state.dialog === dialog)
                                    dialog.copy(
                                        busy = false,
                                        error = message(UiR.string.workspace_preferences_failed),
                                    )
                                else state.dialog,
                        ),
                    )
            }
        }
    }

    fun manageSession(
        id: String,
        operation: WorkspaceSessionOperation,
        openAfterRestore: Boolean = false,
    ) {
        if (!state.connected || state.loading || state.writing || state.error != null) return
        update(
            state.copy(
                sessionDialog =
                    WorkspaceSessionDialogState(id, operation, openAfterRestore = openAfterRestore),
            ),
        )
    }

    fun stopActivity(stop: Boolean) {
        state.sessionDialog
            ?.takeIf { !it.busy }
            ?.let { update(state.copy(sessionDialog = it.copy(stopActivity = stop))) }
    }

    fun dismissSession() {
        if (!state.writing) update(state.copy(sessionDialog = null))
    }

    fun confirmSession() {
        val dialog = state.sessionDialog?.takeIf { !it.busy } ?: return
        changeCollection { client ->
            client.changeSession(dialog.sessionId, dialog.operation, dialog.stopActivity)
        }
    }

    fun manageSessions(ids: List<String>, operation: WorkspaceSessionOperation) {
        changeCollection(ids.distinct()) { client, id ->
            client.changeSession(id, operation, stopActivity = true)
        }
    }

    private fun changeCollection(request: suspend (WorkspacesRepository) -> WorkspaceEvent) {
        changeCollection(listOf(Unit), state.sessionDialog) { client, _ -> request(client) }
    }

    private fun <T> changeCollection(
        items: List<T>,
        dialog: WorkspaceSessionDialogState? = null,
        request: suspend (WorkspacesRepository, T) -> WorkspaceEvent,
    ) {
        if (items.isEmpty()) return
        val client = owner ?: return
        val own = connection
        if (!current(client, own) || state.loading || state.writing || state.error != null) return
        val repository = client
        update(
            state.copy(
                writing = true,
                error = null,
                sessionDialog = dialog?.copy(busy = true, error = null),
            ),
        )
        write = scope.launch {
            val success =
                repository.changeCollection(items, request) {
                    if (current(client, own) && repository === owner) emit()
                } ?: return@launch
            if (!current(client, own) || repository !== owner) return@launch
            if (success) {
                update(state.copy(writing = false, sessionDialog = null))
                emit()
                if (
                    dialog?.openAfterRestore == true &&
                        dialog.operation == WorkspaceSessionOperation.Restore &&
                        dialog.sessionId !in repository.inventory.archived
                )
                    onRestored(dialog.sessionId)
            } else {
                val text = message(UiR.string.workspace_session_failed)
                update(
                    state.copy(
                        writing = false,
                        sessionDialog = dialog?.copy(error = text),
                        error = if (dialog == null) text else state.error,
                    ),
                )
            }
        }
    }

    private fun emit(loading: Boolean = state.loading) {
        val inventory = owner?.inventory ?: WorkspaceInventory()
        val rows = inventory.rows
        val selected =
            if (loading || !state.preferencesLoaded) state.selectedId
            else state.selectedId?.takeIf { id -> rows.any { it.id == id } }
        update(
            state.copy(
                rows = rows,
                selectedId = selected,
                archived = inventory.archived,
                pinned = inventory.pinned,
                loading = loading,
                loaded = state.loaded || !loading,
            ),
        )
    }

    private fun current(client: WorkspacesRepository, own: Long) =
        owner === client &&
            own == connection &&
            generation != null &&
            client.generation == generation

    private fun update(value: WorkspacesUiState) {
        state = value.copy(revision = state.revision + 1)
        publish(state)
    }

    override fun onCleared() {
        close()
    }

    fun close() {
        connection++
        watch?.cancel()
        write?.cancel()
        preferencesWatch?.cancel()
        owner = null
        generation = null
    }
}

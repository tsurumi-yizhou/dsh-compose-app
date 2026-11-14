package dev.fughetta.dshcompose.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The catalog owns data and loading outcomes; this page owns the user's refresh request. */
class SessionsViewModel(
    private val repository: SessionsRepository,
    parent: CoroutineScope? = null,
    private val offlineRename: suspend (String, String) -> String? = { _, _ -> null },
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    val uiState: StateFlow<SessionCatalogState> = repository.state
    private var refresh: Job? = null

    fun handle(action: AppAction): Boolean {
        if (action != AppAction.RefreshSessions || !repository.state.value.available) return false
        if (refresh?.isActive != true)
            refresh = scope.launch {
                try {
                    repository.refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SessionOperationException) {
                    // The repository publishes the read failure for every catalog observer.
                }
            }
        return true
    }

    private class ActionConnection(val operations: SessionsRepository, var connected: Boolean)

    private var actionConnection: ActionConnection? = null
    private var opened: ActionConnection? = null
    private var actionRequest: Job? = null
    private val dialogMutable = MutableStateFlow<SessionDialogState?>(null)
    val dialogState = dialogMutable.asStateFlow()

    fun connect(operations: SessionsRepository, connected: Boolean) {
        disconnect()
        actionConnection = ActionConnection(operations, connected)
        connectDeletion(operations)
    }

    fun disconnect() {
        actionConnection = null
        opened = null
        actionRequest?.cancel()
        actionRequest = null
        dialogMutable.value = null
        disconnectDeletion()
    }

    fun connected(value: Boolean) {
        actionConnection?.connected = value
    }

    fun open(id: String, operation: SessionOperation, title: String) {
        if (dialogMutable.value?.busy == true || deletionState.value.busy) return
        opened = actionConnection
        dialogMutable.value = SessionDialogState(id, operation, title)
    }

    fun editTitle(title: String) {
        dialogMutable.update {
            it?.let { before ->
                if (before.busy) before else before.copy(title = title, errorCode = null)
            }
        }
    }

    fun dismiss() {
        if (dialogMutable.value?.busy == true) return
        dialogMutable.value = null
        opened = null
    }

    fun confirm(
        renamed: (String, String) -> Unit,
        forked: (String) -> Unit,
        deleted: suspend (String) -> Unit,
    ) {
        if (deletionState.value.busy) return
        val before = dialogMutable.value?.takeIf { !it.busy } ?: return
        val owner = opened?.takeIf {
            it === actionConnection && it.connected && it.operations.active
        }
        if (owner == null) {
            confirmOffline(before, renamed)
            return
        }
        if (before.operation == SessionOperation.Rename && before.title.isBlank()) return
        dialogMutable.value = before.copy(busy = true, errorCode = null)
        actionRequest = scope.launch {
            try {
                when (before.operation) {
                    SessionOperation.Rename -> {
                        val title = owner.operations.rename(before.sessionId, before.title.trim())
                        if (actionConnection !== owner) return@launch
                        renamed(before.sessionId, title)
                    }
                    SessionOperation.Fork -> {
                        val id = owner.operations.forkSession(before.sessionId)
                        if (actionConnection !== owner) return@launch
                        forked(id)
                    }
                    SessionOperation.Delete -> deleteSingle(before.sessionId, deleted)
                }
                if (actionConnection === owner) {
                    dialogMutable.value = null
                    opened = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionOperationException) {
                if (actionConnection === owner)
                    dialogMutable.value = before.copy(errorCode = error.code)
            } finally {
                if (actionConnection === owner) dialogMutable.update { it?.copy(busy = false) }
            }
        }
    }

    /**
     * No link: only a rename can be saved, because the mirror takes the new title immediately and
     * the outbox replays it on the next connection. Everything else needs the engine answer.
     */
    private fun confirmOffline(before: SessionDialogState, renamed: (String, String) -> Unit) {
        if (before.operation != SessionOperation.Rename) {
            dialogMutable.value = before.copy(errorCode = "not-connected")
            return
        }
        if (before.title.isBlank()) return
        dialogMutable.value = before.copy(busy = true, errorCode = null)
        actionRequest = scope.launch {
            try {
                val title =
                    offlineRename(before.sessionId, before.title.trim())
                        ?: throw SessionOperationException("not-connected")
                renamed(before.sessionId, title)
                dialogMutable.value = null
                opened = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionOperationException) {
                dialogMutable.value = before.copy(errorCode = error.code)
            } finally {
                dialogMutable.update { it?.copy(busy = false) }
            }
        }
    }

    private class DeletionConnection(val operations: SessionsRepository)

    private var deletionConnection: DeletionConnection? = null
    private var deletionConfirmation: DeletionConnection? = null
    private var deletionOperation: Deferred<SessionDeletionResult>? = null
    private val deletionMutable = MutableStateFlow(SessionDeletionState())
    val deletionState = deletionMutable.asStateFlow()

    fun connectDeletion(operations: SessionsRepository) {
        disconnectDeletion()
        deletionConnection = DeletionConnection(operations)
    }

    fun disconnectDeletion() {
        deletionConnection = null
        deletionConfirmation = null
        deletionOperation?.cancel()
        deletionOperation = null
        deletionMutable.value = SessionDeletionState()
    }

    fun resetDeletionSupport() {
        deletionMutable.update { it.copy(support = SessionDeletionSupport.Unknown) }
    }

    fun requestDeletion(ids: List<String>) {
        if (deletionMutable.value.busy) return
        deletionConfirmation = deletionConnection
        deletionMutable.value =
            SessionDeletionState(
                support = deletionMutable.value.support,
                sessionIds = ids.distinct().filterNot { it == DRAFT_SESSION_ID },
                confirming = true,
            )
    }

    fun dismissDeletion() {
        if (deletionMutable.value.busy) return
        deletionConfirmation = null
        deletionMutable.value = SessionDeletionState(support = deletionMutable.value.support)
    }

    fun confirmDeletion(confirmed: suspend (String) -> Unit) {
        val before = deletionMutable.value
        if (before.busy || !before.confirming || before.sessionIds.isEmpty()) return
        val owner = deletionConfirmation?.takeIf {
            it === deletionConnection && it.operations.active
        }
        if (owner == null) {
            deletionMutable.update {
                it.copy(confirming = false, result = SessionDeletionResult(retired = true))
            }
            return
        }
        startDeletion(owner, before.sessionIds, confirmed)
    }

    suspend fun deleteSingle(id: String, confirmed: suspend (String) -> Unit) {
        if (deletionMutable.value.busy) throw SessionOperationException("operation-in-progress")
        val owner =
            deletionConnection?.takeIf { it.operations.active }
                ?: throw SessionOperationException("connection-lost")
        val result = startDeletion(owner, listOf(id), confirmed, showFailures = false).await()
        val failure = result.failures[id]
        if (failure != null) throw SessionOperationException(failure)
        if (result.retired && id !in result.deleted)
            throw SessionOperationException("connection-lost")
    }

    private fun startDeletion(
        owner: DeletionConnection,
        ids: List<String>,
        confirmed: suspend (String) -> Unit,
        showFailures: Boolean = true,
    ): Deferred<SessionDeletionResult> {
        deletionConfirmation = null
        deletionMutable.update {
            it.copy(
                sessionIds = ids,
                confirming = false,
                busy = true,
                result = null,
                resultDialog = false,
            )
        }
        val task =
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        DeleteSessionsUseCase(owner.operations)(
                            ids,
                            { id ->
                                if (deletionConnection === owner)
                                    deletionMutable.update { it.copy(deleting = setOf(id)) }
                            },
                            { id -> if (deletionConnection === owner) confirmed(id) },
                        )
                    if (deletionConnection === owner)
                        deletionMutable.update {
                            it.copy(
                                result = result,
                                resultDialog =
                                    (showFailures && result.failures.isNotEmpty()) ||
                                        result.cleanupFailures.isNotEmpty(),
                                support =
                                    when {
                                        result.deleted.isNotEmpty() ->
                                            SessionDeletionSupport.Supported
                                        result.failures.values.any { code ->
                                            code == "unavailable"
                                        } -> SessionDeletionSupport.Unsupported
                                        else -> it.support
                                    },
                            )
                        }
                    result
                } finally {
                    if (deletionConnection === owner)
                        deletionMutable.update { it.copy(busy = false, deleting = emptySet()) }
                }
            }
        deletionOperation = task
        task.start()
        return task
    }

    override fun onCleared() {
        refresh?.cancel()
        disconnect()
    }
}

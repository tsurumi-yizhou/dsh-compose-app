package dev.fughetta.dshcompose.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.permissions.PermissionsRepository
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.*

/**
 * Selection badges always come from a Host read; commands and revisioned settings never update them
 * optimistically.
 */
class PermissionsViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val autoReviewConfirmed: suspend () -> Boolean,
    private val rememberAutoReview: suspend () -> Unit,
    private val publish: (PermissionsUiState?) -> Unit,
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var client: PermissionsRepository? = null
    private var state: PermissionsUiState? = null
    private var epoch = 0L
    private var readEpoch = 0L
    private var observing = false
    private var read: Job? = null
    private var mutation: Job? = null
    private var consentRead: Job? = null
    private var invalidated = false

    fun connect(value: PermissionsRepository?) {
        if (client !== value) {
            closeDialog()
            client = value
        }
    }

    fun open(sessionId: String?) {
        closeDialog()
        state = PermissionsUiState(sessionId)
        publish(state)
    }

    fun closeDialog() {
        epoch++
        readEpoch++
        read?.cancel()
        mutation?.cancel()
        consentRead?.cancel()
        observing = false
        invalidated = false
        state = null
        publish(null)
    }

    fun observe(value: Boolean) {
        if (observing == value) return
        observing = value
        if (value) refresh()
        else {
            readEpoch++
            read?.cancel()
        }
    }

    fun sessionChanged(id: String) {
        if (state?.sessionId == id) invalidate()
    }

    fun invalidate() {
        if (state?.busy == true) invalidated = true else if (observing) refresh()
    }

    fun refresh() {
        val view = state ?: return
        val owner =
            client
                ?: return update {
                    it.copy(
                        loading = false,
                        stale = true,
                        error = message(CoreDesignsystemR.string.engine_not_connected),
                    )
                }
        if (!observing || view.busy) return
        read?.cancel()
        val own = epoch
        val revision = ++readEpoch
        update { it.copy(loading = true, error = null) }
        read = scope.launch {
            try {
                val snapshot = withTimeout(20000) { owner.snapshot(view.sessionId) }
                if (current(owner, own) && revision == readEpoch)
                    update {
                        it.copy(snapshot = snapshot, loading = false, stale = false, notice = null)
                    }
            } catch (_: TimeoutCancellationException) {
                if (current(owner, own) && revision == readEpoch)
                    update {
                        it.copy(
                            loading = false,
                            stale = true,
                            error = message(R.string.permissions_read_failed),
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own) && revision == readEpoch)
                    update { it.copy(loading = false, stale = true, error = code(error)) }
            }
        }
    }

    fun choose(value: String, target: PermissionTarget) {
        val owner = client ?: return
        val view = state ?: return
        if (
            view.loading ||
                view.busy ||
                view.stale ||
                view.pending != null ||
                !allowed(view, value, target)
        )
            return
        val change = PermissionChange(value, target, view.snapshot?.defaults?.revision)
        if (value == "danger-full-access") {
            update { it.copy(pending = change) }
            return
        }
        val own = epoch
        if (value == "auto") {
            update { it.copy(busy = true, error = null) }
            consentRead = scope.launch {
                try {
                    val accepted = autoReviewConfirmed()
                    if (!current(owner, own)) return@launch
                    update { it.copy(busy = false) }
                    if (accepted) apply(change) else update { it.copy(pending = change) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (current(owner, own))
                        update {
                            it.copy(
                                busy = false,
                                error = message(R.string.permissions_change_failed),
                            )
                        }
                }
            }
        } else apply(change)
    }

    fun remember(value: Boolean) {
        update { it.copy(pending = it.pending?.copy(remember = value)) }
    }

    fun cancel() {
        update { it.copy(pending = null) }
    }

    fun confirm() {
        state?.pending?.let { apply(it, confirmed = true) }
    }

    private fun allowed(
        view: PermissionsUiState,
        value: String,
        target: PermissionTarget,
    ): Boolean {
        val snapshot = view.snapshot ?: return false
        return when (target) {
            PermissionTarget.Current ->
                view.sessionId != null && snapshot.catalog.options.any { it.value == value }
            PermissionTarget.Default ->
                snapshot.defaults?.writable == true &&
                    snapshot.catalog.defaultOptions.any { it.value == value }
        }
    }

    private fun apply(change: PermissionChange, confirmed: Boolean = false) {
        val owner = client ?: return
        val view = state ?: return
        if (view.busy || view.loading || view.stale) return
        if (
            !allowed(view, change.value, change.target) ||
                change.target == PermissionTarget.Default &&
                    view.snapshot?.defaults?.revision != change.revision
        ) {
            update { it.copy(pending = null, error = message(R.string.permissions_changed)) }
            return
        }
        val own = epoch
        read?.cancel()
        readEpoch++
        invalidated = false
        update { it.copy(busy = true, pending = null, error = null, notice = null) }
        mutation = scope.launch {
            var applied = false
            try {
                if (confirmed && change.value == "auto" && change.remember) rememberAutoReview()
                if (!current(owner, own)) return@launch
                val result =
                    owner.change(
                        view.sessionId,
                        change.value,
                        if (change.target == PermissionTarget.Default) view.snapshot!!.defaults!!
                        else null,
                    ) {
                        current(owner, own)
                    } ?: return@launch
                applied = result.applied
                result.failure?.let { throw it }
                val snapshot = checkNotNull(result.snapshot)
                if (current(owner, own))
                    update {
                        it.copy(
                            snapshot = snapshot,
                            stale = false,
                            error =
                                if (applied) null else message(R.string.permissions_change_failed),
                        )
                    }
            } catch (error: TimeoutCancellationException) {
                failed(owner, own, applied, error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed(owner, own, applied, error)
            } finally {
                if (current(owner, own)) {
                    update { it.copy(busy = false) }
                    if (invalidated && observing) {
                        invalidated = false
                        refresh()
                    }
                }
            }
        }
    }

    private fun failed(
        owner: PermissionsRepository,
        own: Long,
        applied: Boolean,
        error: Exception,
    ) {
        if (current(owner, own))
            update {
                it.copy(
                    stale = true,
                    error =
                        if (applied) null
                        else
                            operationErrorCode(error)
                                ?: message(R.string.permissions_change_failed),
                    notice = if (applied) message(R.string.permissions_unconfirmed) else null,
                )
            }
    }

    private fun current(owner: PermissionsRepository, own: Long) =
        client === owner && own == epoch && state != null

    private fun update(transform: (PermissionsUiState) -> PermissionsUiState) {
        state = state?.let(transform)
        publish(state)
    }

    private fun code(error: Exception) =
        operationErrorCode(error) ?: message(R.string.permissions_read_failed)

    override fun onCleared() {
        close()
    }

    fun close() {
        closeDialog()
        client = null
    }
}

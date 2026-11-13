package dev.fughetta.dshcompose.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.domain.sessions.sessionLogZipName
import dev.fughetta.dshcompose.ui.*
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*

class SessionArchiveException(val code: String) : java.io.IOException(code)

data class SessionArchiveResult(val savedTo: String? = null, val share: SessionArchiveShare? = null)

/**
 * Closing an unpublished target removes partial bytes; commit hands ownership to Downloads or the
 * share cache.
 */
class SessionArchiveDestination(
    val output: OutputStream,
    private val publish: suspend () -> SessionArchiveResult,
    private val finish: (committed: Boolean) -> Unit,
) : AutoCloseable {
    private var committed = false

    suspend fun commit(): SessionArchiveResult {
        output.close()
        currentCoroutineContext().ensureActive()
        val result = publish()
        committed = true
        return result
    }

    override fun close() {
        try {
            output.close()
        } finally {
            finish(committed)
        }
    }
}

class SessionExportViewModel(
    parent: CoroutineScope? = null,
    private val destination: suspend (SessionExportOperation, String) -> SessionArchiveDestination,
    private val legacyPermission: () -> Boolean,
    private val hasPermission: () -> Boolean,
    private val publish: (SessionExportUiState?) -> Unit,
) : ViewModel() {
    private val scope = parent ?: viewModelScope

    override fun onCleared() {
        close()
    }

    private var client: SessionsRepository? = null
    private var epoch = 0L
    private var state: SessionExportUiState? = null
    private var transfer: Job? = null
    private var permission: CompletableDeferred<Boolean>? = null
    private var permissionPrompt: String? = null
    private var shareClaim: String? = null

    fun connect(value: SessionsRepository?) {
        if (client === value) return
        dismiss()
        client = value
    }

    fun open(sessionId: String) {
        if (transfer?.isActive == true) return
        dismiss()
        val owner = client ?: return
        update(
            SessionExportUiState(
                sessionId = sessionId,
                id = UUID.randomUUID().toString(),
                supported = owner.supported,
                name = sessionLogZipName(sessionId),
            ),
        )
    }

    fun run(operation: SessionExportOperation) {
        val owner = client ?: return
        val view = state?.takeIf { it.supported && !it.busy } ?: return
        val own = ++epoch
        val id = UUID.randomUUID().toString()
        shareClaim = null
        update(
            view.copy(
                id = id,
                busy = true,
                operation = operation,
                bytes = 0,
                total = null,
                needsPermission = false,
                share = null,
                savedTo = null,
                shared = false,
                error = null,
            ),
        )
        transfer = scope.launch {
            try {
                if (
                    operation == SessionExportOperation.Save &&
                        legacyPermission() &&
                        !hasPermission()
                ) {
                    val grant = CompletableDeferred<Boolean>()
                    permission = grant
                    update(state!!.copy(needsPermission = true))
                    try {
                        if (!grant.await()) throw SessionArchiveException("permission")
                    } finally {
                        if (permission === grant) permission = null
                        grant.cancel()
                    }
                    if (!current(owner, own)) return@launch
                    update(state!!.copy(needsPermission = false))
                }
                val receipt =
                    owner.transferArchive(
                        view.sessionId,
                        destination = { destination(operation, view.name) },
                        output = { it.output::write },
                        commit = { it.commit() },
                        current = { current(owner, own) },
                        progress = { bytes, total ->
                            update(state!!.copy(bytes = bytes, total = total))
                        },
                    ) ?: return@launch
                val result = receipt.result
                if (current(owner, own))
                    update(
                        state!!.copy(
                            busy = false,
                            bytes = receipt.bytes,
                            share = result.share,
                            savedTo = result.savedTo,
                        ),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own))
                    update(
                        state!!.copy(
                            busy = false,
                            needsPermission = false,
                            error =
                                operationErrorCode(error)
                                    ?: when (error) {
                                        is SessionArchiveException -> error.code
                                        else -> "session-export/failed"
                                    },
                        ),
                    )
            }
        }
    }

    fun claimPermission(id: String): Boolean {
        if (
            state?.id != id ||
                state?.needsPermission != true ||
                permission?.isCompleted != false ||
                permissionPrompt != null
        )
            return false
        permissionPrompt = id
        return true
    }

    fun permissionResult(granted: Boolean) {
        val id = permissionPrompt ?: return
        permissionPrompt = null
        if (state?.id == id) permission?.complete(granted)
        // A newer export may be waiting behind an old Android permission dialog.
        state?.let { update(it) }
    }

    fun claimShare(id: String): Boolean {
        if (state?.id != id || state?.share == null || shareClaim == id) return false
        shareClaim = id
        return true
    }

    fun shared(id: String, launched: Boolean) {
        if (state?.id == id && shareClaim == id)
            update(
                state!!.copy(
                    share = null,
                    shared = launched,
                    error = if (launched) null else "session-export/share",
                ),
            )
    }

    private fun current(owner: SessionsRepository, own: Long) =
        client === owner && epoch == own && state != null

    private fun update(value: SessionExportUiState?) {
        state = value?.copy(revision = (state?.revision ?: 0) + 1)
        publish(state)
    }

    fun dismiss() {
        epoch++
        transfer?.cancel()
        permission?.cancel()
        permission = null
        shareClaim = null
        update(null)
    }

    fun close() {
        dismiss()
        client = null
    }
}

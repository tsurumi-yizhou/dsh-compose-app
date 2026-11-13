@file:Suppress("REDUNDANT_ELSE_IN_WHEN")

package dev.fughetta.dshcompose.transcript

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifact
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFileChange
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.files.GuestFiles
import dev.fughetta.dshcompose.runtime.PhoneStorage
import dev.fughetta.dshcompose.ui.transcript.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Each action captures Host/client/generation; files are never resolved on the phone for a remote
 * compose service.
 */
class TranscriptArtifactViewModel(parent: CoroutineScope? = null, context: Context) : ViewModel() {
    private val scope = parent ?: viewModelScope

    override fun onCleared() {
        close()
    }

    private val app = context.applicationContext
    private val mutable = MutableStateFlow(TranscriptArtifactState())
    val state: StateFlow<TranscriptArtifactState> = mutable.asStateFlow()

    private data class Event(val epoch: Long, val value: TranscriptArtifactEffect)

    private val channel = Channel<Event>(8)
    val effects = channel.receiveAsFlow().filter { it.epoch == epoch }.map { it.value }
    private var files: WorkspaceFilesRepository? = null
    private var host: String? = null
    private var local = false
    private var epoch = 0L
    private var operation: Job? = null
    private var tableJob: Job? = null
    private var watch: Job? = null

    private data class Pending(
        val session: String,
        val artifact: TranscriptArtifact,
        val action: TranscriptArtifactAction,
        val epoch: Long,
    )

    private var pending: Pending? = null

    fun connect(owner: WorkspaceFilesRepository?, hostKey: String?, isLocal: Boolean) {
        if (files === owner && host == hostKey && local == isLocal) return
        epoch++
        operation?.cancel()
        tableJob?.cancel()
        watch?.cancel()
        pending = null
        files = owner
        host = hostKey
        local = isLocal
        mutable.value = TranscriptArtifactState()
    }

    fun action(sessionId: String, file: TranscriptArtifact, action: TranscriptArtifactAction) {
        val owner = files ?: return fail("artifact/offline")
        if (operation?.isActive == true) return
        val own = epoch
        val generation = owner.generation
        val isLocal = local
        fun current() = files === owner && epoch == own && owner.generation == generation
        if (action == TranscriptArtifactAction.Preview) {
            val ext = file.path.substringAfterLast('.').lowercase()
            if (ext in setOf("csv", "tsv", "xlsx", "xlsm")) openTable(sessionId, file.path)
            else offer(TranscriptArtifactEffect.Preview(sessionId, file.path))
            return
        }
        if (
            action == TranscriptArtifactAction.Save &&
                Build.VERSION.SDK_INT < 29 &&
                !PhoneStorage.isGranted(app)
        ) {
            pending = Pending(sessionId, file, action, own)
            offer(TranscriptArtifactEffect.StoragePermission)
            return
        }
        mutable.value =
            state.value.copy(
                busy = true,
                error = null,
                notice = null,
                bytes = 0,
                total = file.bytes,
            )
        operation = scope.launch {
            try {
                val stat = owner.stat(sessionId, file.path)
                if (!current()) return@launch
                if (action == TranscriptArtifactAction.CopyPath) {
                    emit(TranscriptArtifactEffect.CopyPath(stat.absolutePath))
                    return@launch
                }
                val existing =
                    if (isLocal)
                        withContext(Dispatchers.IO) { GuestFiles.resolve(app, stat.absolutePath) }
                    else null
                if (
                    action == TranscriptArtifactAction.Save &&
                        isLocal &&
                        existing != null &&
                        stat.absolutePath.startsWith("/sdcard/Download/")
                ) {
                    mutable.value = state.value.copy(notice = "artifact/saved")
                    return@launch
                }
                owner.withArtifactFile(
                    sessionId,
                    file.path,
                    file.name,
                    app.cacheDir.absolutePath,
                    existing?.absolutePath,
                    progress = { bytes, total ->
                        withContext(Dispatchers.Main.immediate) {
                            if (current())
                                mutable.value = state.value.copy(bytes = bytes, total = total)
                        }
                    },
                ) { sourcePath ->
                    val source = java.io.File(sourcePath)
                    if (!current()) return@withArtifactFile false
                    val mime =
                        owner.mediaType(
                            null,
                            file.name,
                        )
                    when (action) {
                        TranscriptArtifactAction.Open,
                        TranscriptArtifactAction.OpenWith -> {
                            emit(
                                TranscriptArtifactEffect.Open(
                                    source.absolutePath,
                                    mime,
                                    action == TranscriptArtifactAction.OpenWith,
                                ),
                            )
                            true
                        }
                        TranscriptArtifactAction.Share -> {
                            emit(TranscriptArtifactEffect.Share(source.absolutePath, mime))
                            true
                        }
                        TranscriptArtifactAction.Save -> {
                            withContext(Dispatchers.IO) {
                                saveArtifact(app, source, file.name, mime)
                            }
                            if (current())
                                mutable.value = state.value.copy(notice = "artifact/saved")
                            false
                        }
                        else -> false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current()) fail(operationErrorCode(error) ?: "artifact/failed")
            } finally {
                if (epoch == own) mutable.value = state.value.copy(busy = false)
            }
        }
    }

    fun storagePermission(granted: Boolean) {
        val request = pending
        pending = null
        if (request == null || request.epoch != epoch) return
        if (granted && PhoneStorage.isGranted(app))
            action(request.session, request.artifact, request.action)
        else fail("artifact/storage-denied")
    }

    private fun openTable(sessionId: String, path: String) {
        tableJob?.cancel()
        watch?.cancel()
        mutable.value = state.value.copy(sheet = TranscriptSheetPreview(sessionId, path))
        loadTable()
        val owner = files ?: return
        val own = epoch
        watch = scope.launch {
            try {
                owner.changes(sessionId, path).collect { change ->
                    if (epoch == own && change is WorkspaceFileChange.Changed) {
                        val view = state.value.sheet
                        if (view != null && change.version != view.version)
                            mutable.value = state.value.copy(sheet = view.copy(changed = true))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (epoch == own)
                    state.value.sheet?.let {
                        mutable.value =
                            state.value.copy(sheet = it.copy(error = "artifact/watch-lost"))
                    }
            }
        }
    }

    fun refreshTable() {
        tableJob?.cancel()
        loadTable()
    }

    private fun loadTable() {
        val view = state.value.sheet ?: return
        val owner = files ?: return
        val own = epoch
        val generation = owner.generation
        mutable.value = state.value.copy(sheet = view.copy(loading = true, error = null))
        tableJob = scope.launch {
            try {
                val table =
                    owner.readArtifactTable(
                        view.sessionId,
                        view.path,
                        app.cacheDir.absolutePath,
                        delimited = { bytes, tsv, partial ->
                            persistentListOf(TranscriptTables.delimited(bytes, tsv, partial))
                        },
                        workbook = { TranscriptTables.xlsx(java.io.File(it)) },
                    )
                val sheets = table.sheets
                val version = table.version
                if (
                    epoch == own &&
                        files === owner &&
                        owner.generation === generation &&
                        state.value.sheet?.path == view.path
                )
                    mutable.value =
                        state.value.copy(
                            sheet =
                                view.copy(
                                    sheets = sheets,
                                    loading = false,
                                    version = version,
                                    changed = false,
                                    selected =
                                        view.selected.coerceIn(
                                            0,
                                            (sheets.size - 1).coerceAtLeast(0),
                                        ),
                                ),
                        )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == own && state.value.sheet?.path == view.path)
                    mutable.value =
                        state.value.copy(
                            sheet =
                                view.copy(
                                    loading = false,
                                    error = operationErrorCode(error) ?: "artifact/invalid-table",
                                ),
                        )
            }
        }
    }

    fun selectSheet(index: Int) {
        val view = state.value.sheet ?: return
        if (index in view.sheets.indices)
            mutable.value = state.value.copy(sheet = view.copy(selected = index))
    }

    fun closeTable() {
        tableJob?.cancel()
        watch?.cancel()
        mutable.value = state.value.copy(sheet = null)
    }

    private fun offer(effect: TranscriptArtifactEffect) {
        channel.trySend(Event(epoch, effect))
    }

    private suspend fun emit(effect: TranscriptArtifactEffect) {
        channel.send(Event(epoch, effect))
    }

    fun platformFailed() {
        fail("artifact/handler-unavailable")
    }

    private fun fail(code: String) {
        mutable.value = state.value.copy(error = code, busy = false)
    }

    fun close() {
        epoch++
        operation?.cancel()
        tableJob?.cancel()
        watch?.cancel()
        pending = null
        channel.close()
    }
}

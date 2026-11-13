package dev.fughetta.dshcompose.files

import android.content.Context
import android.net.Uri
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.attachments.attachmentFileName
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.ui.WorkspaceExportUiState
import kotlinx.coroutines.*

/**
 * A document-provider grant lasts only for this transfer; no credentials or destination are
 * persisted.
 */
class WorkspaceFileExporter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val publish: (WorkspaceExportUiState?) -> Unit,
) : AutoCloseable {
    private data class Pending(
        val sessionId: String,
        val path: String,
        val name: String,
        val epoch: Long,
    )

    private var owner: WorkspaceFilesRepository? = null
    private var generation: Any? = null
    private var epoch = 0L
    private var choosing = false
    private var pending: Pending? = null
    private var transfer: Job? = null
    private var state: WorkspaceExportUiState? = null

    fun connect(client: WorkspaceFilesRepository?) {
        val lease = client?.generation
        if (owner === client && generation == lease) return
        epoch++
        transfer?.cancel()
        pending = null
        update(null)
        owner = client
        generation = lease
    }

    fun prepare(sessionId: String, path: String): String? {
        if (
            choosing ||
                transfer?.isActive == true ||
                owner == null ||
                generation == null ||
                owner?.generation != generation
        )
            return null
        val name = attachmentFileName(path)
        pending = Pending(sessionId, path, name, epoch)
        choosing = true
        return name
    }

    fun picked(uri: Uri?) {
        choosing = false
        val request = pending
        pending = null
        val client = owner
        if (uri == null || request == null || client == null || request.epoch != epoch) return
        val own = epoch
        update(WorkspaceExportUiState(request.name))
        transfer = scope.launch {
            try {
                val copied =
                    withContext(Dispatchers.IO) {
                        val output =
                            context.contentResolver.openOutputStream(uri, "w")
                                ?: throw java.io.IOException("Destination unavailable")
                        output.use { destination ->
                            client.copy(
                                request.sessionId,
                                request.path,
                                destination::write,
                                maximumBytes = Long.MAX_VALUE,
                            ) { bytes, total ->
                                withContext(Dispatchers.Main.immediate) {
                                    if (current(client, own))
                                        update(state!!.copy(bytes = bytes, total = total))
                                }
                            }
                        }
                    }
                if (current(client, own))
                    update(state!!.copy(busy = false, bytes = copied.bytes, saved = true))
            } catch (error: CancellationException) {
                if (current(client, own))
                    update(
                        state!!.copy(
                            busy = false,
                            error = context.getString(UiR.string.workspace_export_incomplete),
                        ),
                    )
                throw error
            } catch (error: Exception) {
                if (current(client, own))
                    update(
                        state!!.copy(
                            busy = false,
                            error =
                                context.getString(
                                    if (operationErrorCode(error) == "workspace-file/changed")
                                        UiR.string.workspace_export_changed
                                    else UiR.string.workspace_export_incomplete,
                                ),
                        ),
                    )
            }
        }
    }

    fun pickerFailed() {
        choosing = false
        val name = pending?.name ?: return
        pending = null
        update(
            WorkspaceExportUiState(
                name,
                busy = false,
                error = context.getString(UiR.string.workspace_export_picker_failed),
            ),
        )
    }

    fun dismiss() {
        if (transfer?.isActive == true) transfer?.cancel() else update(null)
    }

    private fun current(client: WorkspaceFilesRepository, own: Long) =
        owner === client && epoch == own && client.generation == generation && state != null

    private fun update(value: WorkspaceExportUiState?) {
        state = value
        publish(value)
    }

    override fun close() {
        epoch++
        transfer?.cancel()
        pending = null
        owner = null
        generation = null
        update(null)
    }
}

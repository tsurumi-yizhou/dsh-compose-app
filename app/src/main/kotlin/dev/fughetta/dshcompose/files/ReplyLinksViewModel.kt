package dev.fughetta.dshcompose.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.links.ReplyLinkTarget
import dev.fughetta.dshcompose.domain.links.replyLinkTarget
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.ui.ReplyLinkUiState
import kotlinx.coroutines.*

/** Resolve only the clicked link; browser URLs never receive the engine's connection or cookies. */
class ReplyLinksViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val publish: (ReplyLinkUiState?) -> Unit,
    private val openFile: (String, String) -> Unit,
    private val openPage: (String) -> Boolean,
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var owner: WorkspaceFilesRepository? = null
    private var generation: Any? = null
    private var connection = -1L
    private var request = 0L
    private var work: Job? = null
    private var state: ReplyLinkUiState? = null

    fun connect(client: WorkspaceFilesRepository?, connection: Long) {
        val lease = client?.generation
        if (owner === client && generation == lease && this.connection == connection) return
        dismiss()
        owner = client
        generation = lease
        this.connection = connection
    }

    fun open(sessionId: String, connection: Long, target: String) {
        if (connection != this.connection) return
        dismiss()
        val number = request
        when (val link = replyLinkTarget(target)) {
            is ReplyLinkTarget.External ->
                update(ReplyLinkUiState(number, sessionId, target, external = link.address))
            is ReplyLinkTarget.Page ->
                if (!openPage(link.id))
                    update(
                        ReplyLinkUiState(
                            number,
                            sessionId,
                            target,
                            error = message(UiR.string.markdown_link_unavailable),
                        ),
                    )
            ReplyLinkTarget.Unsupported ->
                update(
                    ReplyLinkUiState(
                        number,
                        sessionId,
                        target,
                        error = message(UiR.string.markdown_link_unavailable),
                    ),
                )
            is ReplyLinkTarget.Workspace -> {
                val client = owner?.takeIf { generation != null && it.generation == generation }
                if (client == null) {
                    update(
                        ReplyLinkUiState(
                            number,
                            sessionId,
                            target,
                            external = link.website,
                            error =
                                if (link.website == null) message(UiR.string.engine_not_connected)
                                else null,
                        ),
                    )
                } else if (link.website == null) openFile(sessionId, link.path)
                else {
                    val lease = generation
                    update(ReplyLinkUiState(number, sessionId, target, busy = true))
                    work = scope.launch {
                        val path = client.resolveReplyPath(sessionId, link.path)
                        if (current(client, lease, number)) {
                            if (path != null) completeFile(sessionId, path)
                            else
                                update(
                                    ReplyLinkUiState(
                                        number,
                                        sessionId,
                                        target,
                                        external = link.website,
                                    ),
                                )
                        }
                    }
                }
            }
        }
    }

    private fun completeFile(sessionId: String, path: String) {
        request++
        work = null
        update(null)
        openFile(sessionId, path)
    }

    private fun current(client: WorkspaceFilesRepository, lease: Any?, number: Long) =
        owner === client && generation == lease && client.generation == lease && number == request

    private fun update(value: ReplyLinkUiState?) {
        state = value
        publish(value)
    }

    fun dismiss(id: Long? = null) {
        if (id != null && state?.id != id) return
        request++
        work?.cancel()
        work = null
        update(null)
    }

    fun dismissSession(id: String) {
        if (state?.sessionId == id) dismiss()
    }

    override fun onCleared() {
        close()
    }

    fun close() {
        dismiss()
        owner = null
        generation = null
        connection = -1
    }
}

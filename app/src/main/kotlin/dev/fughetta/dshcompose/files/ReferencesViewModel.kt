package dev.fughetta.dshcompose.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate
import dev.fughetta.dshcompose.domain.references.activeReferenceQuery
import dev.fughetta.dshcompose.domain.references.formatFileMention
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.DRAFT_SESSION_ID
import dev.fughetta.dshcompose.ui.composer.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*

class ReferencesViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val publish: (ReferencePickerState?) -> Unit,
    private val insert: (ReferencePickerState, ReferenceInsertion) -> Boolean,
    private val draftCandidates: (String) -> List<SessionReferenceCandidate> = { emptyList() },
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var owner: WorkspaceFilesRepository? = null
    private var generation: Any? = null
    private var request = 0L
    private var work: Job? = null
    private var state: ReferencePickerState? = null

    fun connect(client: WorkspaceFilesRepository?) {
        val lease = client?.generation
        if (owner === client && generation == lease) return
        dismiss()
        owner = client
        generation = lease
    }

    fun open(sessionId: String, draft: String, start: Int, end: Int) {
        val client = owner ?: return
        if (generation == null || client.generation != generation) return
        dismiss()
        val query = if (start == end) activeReferenceQuery(draft, end) else null
        update(
            ReferencePickerState(
                sessionId,
                draft,
                query?.start ?: start.coerceIn(0, draft.length),
                query?.end ?: end.coerceIn(start.coerceIn(0, draft.length), draft.length),
                query = query?.query.orEmpty(),
                pathOnly = query?.pathOnly == true,
                quoted = query?.quoted == true,
            ),
        )
        search(immediate = true)
    }

    fun query(value: String) {
        state?.let {
            update(it.copy(query = value))
            search()
        }
    }

    fun kind(value: ReferenceKind) {
        state
            ?.takeIf { !it.pathOnly || value == ReferenceKind.Files }
            ?.let {
                update(it.copy(kind = value))
                search(immediate = true)
            }
    }

    fun composer(sessionId: String, draft: String, start: Int, end: Int, composing: Boolean) {
        val previous = state
        if (previous != null && !previous.inline) return
        val client = owner ?: return
        if (generation == null || client.generation != generation) return
        val query = if (start == end && !composing) activeReferenceQuery(draft, end) else null
        if (query == null) {
            if (previous?.inline == true) dismiss()
            return
        }
        if (
            previous?.sessionId == sessionId &&
                previous.draft == draft &&
                previous.start == query.start &&
                previous.end == query.end
        )
            return
        val kind =
            if (sessionId == DRAFT_SESSION_ID) ReferenceKind.Conversations
            else if (!query.pathOnly && previous?.sessionId == sessionId) previous.kind
            else ReferenceKind.Files
        update(
            ReferencePickerState(
                sessionId,
                draft,
                query.start,
                query.end,
                kind = kind,
                query = query.query,
                inline = true,
                pathOnly = query.pathOnly,
                quoted = query.quoted,
            ),
        )
        search()
    }

    private fun search(immediate: Boolean = false) {
        val view = state ?: return
        if (view.sessionId == DRAFT_SESSION_ID) {
            work?.cancel()
            request++
            update(
                view.copy(
                    kind = ReferenceKind.Conversations,
                    sessions = draftCandidates(view.query).toPersistentList(),
                    files = persistentListOf(),
                    loading = false,
                    error = null,
                ),
            )
            return
        }
        val client = owner ?: return
        val lease = generation ?: return
        val number = ++request
        work?.cancel()
        update(
            view.copy(
                loading = true,
                error = null,
                files = persistentListOf(),
                sessions = persistentListOf(),
            ),
        )
        work = scope.launch {
            try {
                val result =
                    client.referenceMatches(
                        view.sessionId,
                        view.query,
                        view.kind == ReferenceKind.Conversations,
                        immediate,
                    )
                val next =
                    view.copy(files = result.files, sessions = result.sessions, loading = false)
                if (current(client, lease, number)) update(next)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, lease, number))
                    update(
                        state!!.copy(
                            loading = false,
                            // Keep the engine's code visible so a Host-specific refusal can be
                            // told apart from a dropped connection.
                            error =
                                message(UiR.string.references_read_failed) +
                                    (operationErrorCode(error)?.let { " ($it)" } ?: ""),
                        ),
                    )
            }
        }
    }

    fun chooseFile(path: String) {
        val view = state?.takeIf { !it.loading && it.kind == ReferenceKind.Files } ?: return
        val file = view.files.firstOrNull { it.path == path } ?: return
        val mention = formatFileMention(file.path, file.isDirectory, view.quoted)
        val label = file.path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: file.path
        if (mention == null) update(view.copy(error = message(UiR.string.reference_path_invalid)))
        else
            choose(
                view,
                ReferenceInsertion(
                    mention,
                    if (file.isDirectory) "$label/" else label,
                    if (file.isDirectory) ReferenceSpanKind.Directory else ReferenceSpanKind.File,
                ),
            )
    }

    fun chooseSession(id: String) {
        val view = state?.takeIf { !it.loading && it.kind == ReferenceKind.Conversations } ?: return
        view.sessions
            .firstOrNull { it.sessionId == id }
            ?.let {
                choose(
                    view,
                    ReferenceInsertion(it.mention, it.title, ReferenceSpanKind.Conversation),
                )
            }
    }

    private fun choose(view: ReferencePickerState, mention: ReferenceInsertion) {
        val client = owner ?: return
        val lease = generation ?: return
        if (!current(client, lease, request)) return
        if (insert(view, mention)) dismiss()
        else update(view.copy(error = message(UiR.string.reference_draft_changed)))
    }

    fun dismissSession(id: String) {
        if (state?.sessionId == id) dismiss()
    }

    fun externalDraft(id: String, draft: String?) {
        state?.takeIf { it.inline && it.sessionId == id && it.draft != draft }?.let { dismiss() }
    }

    fun dismiss() {
        request++
        work?.cancel()
        update(null)
    }

    private fun current(client: WorkspaceFilesRepository, lease: Any?, number: Long) =
        owner === client &&
            generation == lease &&
            client.generation == lease &&
            request == number &&
            state != null

    private fun update(value: ReferencePickerState?) {
        state = value
        publish(value)
    }

    override fun onCleared() {
        close()
    }

    fun close() {
        dismiss()
        owner = null
        generation = null
    }
}

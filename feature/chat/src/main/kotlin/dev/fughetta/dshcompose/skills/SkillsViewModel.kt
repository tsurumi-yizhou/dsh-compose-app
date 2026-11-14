package dev.fughetta.dshcompose.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.skills.SkillCatalog
import dev.fughetta.dshcompose.domain.skills.SkillInfo
import dev.fughetta.dshcompose.domain.skills.SkillsPorts
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

class SkillUseContext(
    val workspaces: List<Workspace>,
    val selected: String?,
    val canCreate: Boolean,
    val canUseHere: Boolean,
)

/**
 * Only the visible session's catalog is retained. Skill invocation is a draft edit, never a prompt.
 */
class SkillsViewModel(
    parent: CoroutineScope? = null,
    private val message: (Int) -> String,
    private val context: (String) -> SkillUseContext,
    private val publish: (SkillsUiState?) -> Unit,
    private val insert: (SkillsPorts, String, SkillInfo) -> Boolean,
    private val openSession: (SkillsPorts, String) -> Unit,
    private val selectWorkspace: (String) -> Unit,
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private var client: SkillsPorts? = null
    private var epoch = 0L
    private var readRevision = 0L
    private var filterRevision = 0L
    private var state: SkillsUiState? = null
    private var catalog: SkillCatalog? = null
    private var observing = false
    private var read: Job? = null
    private var filtering: Job? = null
    private var mutation: Job? = null

    fun connect(value: SkillsPorts?) {
        if (client === value) return
        close()
        client = value
    }

    fun open(id: String) {
        if (state?.busy == true) return
        closeDialog()
        state = SkillsUiState(sessionId = id, canUseHere = context(id).canUseHere)
        publish(state)
    }

    fun closeDialog() {
        if (state?.busy == true) return
        clear()
    }

    private fun clear() {
        epoch++
        readRevision++
        filterRevision++
        read?.cancel()
        filtering?.cancel()
        mutation?.cancel()
        observing = false
        catalog = null
        state = null
        publish(null)
    }

    fun observe(value: Boolean) {
        if (observing == value) return
        observing = value
        if (value) refresh()
        else {
            readRevision++
            filterRevision++
            read?.cancel()
            filtering?.cancel()
            catalog = null
            update { it.copy(skills = persistentListOf(), filtering = false, loading = true) }
        }
    }

    fun invalidate() {
        if (observing) refresh()
    }

    fun refresh() {
        val view = state ?: return
        val owner =
            client
                ?: return update {
                    it.copy(
                        loading = false,
                        error = message(CoreDesignsystemR.string.engine_not_connected),
                    )
                }
        if (!observing || view.busy) return
        val own = epoch
        val revision = ++readRevision
        read?.cancel()
        update { it.copy(loading = true, error = null) }
        read = scope.launch {
            try {
                val prepared = owner.skills.catalog(view.sessionId)
                if (!current(owner, own) || revision != readRevision) return@launch
                catalog = prepared
                val skills = prepared.skills
                update {
                    it.copy(
                        loading = false,
                        selected =
                            it.selected?.let { selected ->
                                skills.firstOrNull { it.name == selected.name }
                            },
                        error = null,
                    )
                }
                filter()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own) && revision == readRevision)
                    update { it.copy(loading = false, error = code(error)) }
            }
        }
    }

    fun query(text: String) {
        update { it.copy(query = limitSessionSearchInput(text)) }
        filter()
    }

    private fun filter() {
        val view = state ?: return
        val own = epoch
        val revision = ++filterRevision
        filtering?.cancel()
        val source = catalog
        update { it.copy(filtering = true) }
        filtering = scope.launch {
            val matches =
                withContext(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    source?.filter(view.query) { context.ensureActive() } ?: persistentListOf()
                }
            if (epoch == own && filterRevision == revision)
                update { it.copy(skills = matches, filtering = false) }
        }
    }

    fun view(name: String) {
        if (state?.busy != true)
            catalog
                ?.skills
                ?.firstOrNull { it.name == name }
                ?.let { skill -> update { it.copy(selected = skill) } }
    }

    fun closeDetail() {
        if (state?.busy != true) update { it.copy(selected = null) }
    }

    fun use(fresh: Boolean) {
        val owner = client ?: return
        val view = state ?: return
        val skill =
            view.selected?.takeIf { selected ->
                catalog?.skills?.any { it.name == selected.name } == true
            } ?: return
        if (view.busy || view.creationUncertain || view.createdSessionId != null || view.loading)
            return
        val target = context(view.sessionId)
        if (!fresh) {
            if (!target.canUseHere || !insert(owner, view.sessionId, skill))
                return update { it.copy(error = message(R.string.skills_insert_failed)) }
            openSession(owner, view.sessionId)
            clear()
            return
        }
        if (!target.canCreate)
            return update { it.copy(error = message(R.string.skills_workspace_loading)) }
        val own = epoch
        update { it.copy(busy = true, error = null) }
        mutation = scope.launch {
            var created: String? = null
            try {
                val result =
                    owner.skills.createSession(
                        owner.sessions,
                        skill,
                        view.sessionId,
                        target.workspaces,
                        target.selected,
                    )
                created = result.sessionId
                if (!current(owner, own)) return@launch
                update { it.copy(createdSessionId = created) }
                if (!insert(owner, created, skill)) {
                    update { it.copy(error = message(R.string.skills_insert_failed)) }
                    return@launch
                }
                result.switchedWorkspace?.let(selectWorkspace)
                openSession(owner, created)
                clear()
            } catch (error: TimeoutCancellationException) {
                if (current(owner, own))
                    update {
                        it.copy(
                            creationUncertain = true,
                            error = message(R.string.skills_creation_unknown),
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owner, own))
                    update {
                        it.copy(
                            creationUncertain =
                                created == null && operationErrorCode(error) == null,
                            error =
                                if (created == null && operationErrorCode(error) == null)
                                    message(R.string.skills_creation_unknown)
                                else code(error),
                        )
                    }
            } finally {
                if (current(owner, own)) update { it.copy(busy = false) }
            }
        }
    }

    fun openCreated() {
        val owner = client ?: return
        val id = state?.createdSessionId ?: return
        openSession(owner, id)
        clear()
    }

    private fun update(transform: (SkillsUiState) -> SkillsUiState) {
        state = state?.let {
            transform(it)
                .copy(revision = it.revision + 1, canUseHere = context(it.sessionId).canUseHere)
        }
        publish(state)
    }

    private fun current(owner: SkillsPorts, own: Long) =
        client === owner && epoch == own && state != null

    private fun code(error: Exception) =
        operationErrorCode(error) ?: message(R.string.skills_read_failed)

    override fun onCleared() {
        close()
    }

    fun close() {
        clear()
        client = null
    }
}

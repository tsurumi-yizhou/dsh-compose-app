package dev.fughetta.dshcompose.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AgentsViewModel(
    private val repository: StateFlow<AgentTeamsRepository?>,
    private val catalog: SessionsRepository,
    private val failureMessage: (String) -> String,
    private val restartMessage: () -> String,
    private val onOpen: (AgentTeamsRepository, String) -> Unit,
    private val onError: (String) -> Unit = {},
) : ViewModel() {
    private val scope
        get() = viewModelScope

    private val mutable = MutableStateFlow(AgentsState())
    val uiState = mutable.asStateFlow()
    private var owner: AgentTeamsRepository? = null
    private var lastHost: String? = null
    private var epoch = 0L
    private var observed = false
    private var rows = emptyList<SessionSummary>()
    private var deriving: Job? = null
    private var read: Job? = null
    private var messages: Job? = null
    private var poll: Job? = null
    private var mutation: Job? = null
    private var selectedSubagents: SessionSubagents? = null
    private var refreshQueued = false
    private var readNumber = 0L

    init {
        scope.launch {
            repository.collectLatest { operations ->
                connect(operations)
                operations?.changes?.collect { change ->
                    if (repository.value === operations) onChange(change)
                }
            }
        }
        scope.launch {
            catalog.state.collect {
                rows = it.allSessions
                derive()
            }
        }
    }

    fun handle(action: AppAction): Boolean {
        connect(repository.value)
        return when (action) {
            is AppAction.OpenAgents -> {
                show(action.sessionId)
                false
            }
            is AppAction.Agents -> {
                action(action.action)
                true
            }
            else -> false
        }
    }

    private fun connect(value: AgentTeamsRepository?) {
        if (owner === value) return
        epoch++
        read?.cancel()
        messages?.cancel()
        mutation?.cancel()
        poll?.cancel()
        deriving?.cancel()
        val selected = mutable.value.sessionId.takeIf { value == null || value.hostId == lastHost }
        owner = value
        rows = catalog.state.value.allSessions
        selectedSubagents = null
        refreshQueued = false
        if (value != null) lastHost = value.hostId
        mutable.value = AgentsState(connected = value != null, sessionId = selected)
        if (value != null) {
            derive()
            if (observed) {
                refresh()
                armPoll()
            }
        }
    }

    private fun show(sessionId: String) {
        if (mutable.value.sessionId != sessionId) {
            epoch++
            read?.cancel()
            messages?.cancel()
            mutation?.cancel()
            deriving?.cancel()
            selectedSubagents = null
            refreshQueued = false
            mutable.update {
                it.copy(
                    sessionId = sessionId,
                    leadId = null,
                    parent = null,
                    loading = false,
                    busy = false,
                    team = null,
                    nodes = persistentListOf(),
                    messages = persistentListOf(),
                    unreadable = persistentSetOf(),
                )
            }
        }
        derive()
        refresh()
    }

    private fun onChange(change: AgentTeamChange) {
        when (change) {
            is AgentTeamChange.SessionStatus ->
                if (
                    change.id == mutable.value.leadId ||
                        mutable.value.team?.members?.any { it.id == change.id } == true
                )
                    refresh()
            AgentTeamChange.ProfileChanged -> refresh()
        }
    }

    private fun action(action: AgentsAction) {
        when (action) {
            is AgentsAction.Observe -> {
                observed = action.active
                if (observed) {
                    refresh()
                    armPoll()
                } else {
                    poll?.cancel()
                    readNumber++
                    read?.cancel()
                    read = null
                    messages?.cancel()
                    refreshQueued = false
                    mutable.update { it.copy(loading = false) }
                }
            }
            is AgentsAction.Tab -> {
                mutable.update { it.copy(tab = action.value) }
                if (action.value == AgentsTab.Messages) loadMessages()
            }
            AgentsAction.Refresh -> {
                owner?.invalidateProfile()
                refresh(true)
            }
            AgentsAction.LoadMessages -> loadMessages()
            is AgentsAction.Open -> open(action.route)
            is AgentsAction.OpenSession -> owner?.let { onOpen(it, action.id) }
            is AgentsAction.Interrupt -> mutate { it.interrupt(action.route) }
            AgentsAction.EnableTeam ->
                mutate { operations ->
                    val restart = operations.enableProfile()
                    if (repository.value === operations && restart)
                        mutable.update { it.copy(notice = restartMessage()) }
                }
        }
    }

    private fun open(route: SubagentAddress) {
        val operations = owner ?: return
        if (route.mode == SubagentMode.External) return
        val cut = epoch
        scope.launch {
            try {
                val resolved = operations.prepareOpen(route)
                if (current(operations, cut)) onOpen(operations, resolved.childSessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentTeamOperationException) {
                if (current(operations, cut)) fail(error.code)
            }
        }
    }

    private fun derive() {
        deriving?.cancel()
        val operations = owner ?: return
        val cut = epoch
        val source = rows
        val selected = mutable.value.sessionId ?: return
        val projection = selectedSubagents
        deriving = scope.launch {
            val result =
                withContext(Dispatchers.Default) {
                    BuildAgentLineageUseCase()(source, selected, projection)
                }
            if (current(operations, cut))
                mutable.update {
                    it.copy(
                        nodes = result.nodes.toPersistentList(),
                        parent = result.parent,
                        leadId = result.parent?.parentSessionId ?: selected,
                        running = result.running.toPersistentSet(),
                    )
                }
        }
    }

    private fun refresh(clearError: Boolean = false) {
        if (!observed) return
        val operations = owner ?: return
        val selected = mutable.value.sessionId ?: return
        if (read?.isActive == true) {
            refreshQueued = true
            return
        }
        val cut = epoch
        val number = ++readNumber
        mutable.update { it.copy(loading = true, error = if (clearError) null else it.error) }
        read = scope.launch {
            try {
                val lead =
                    rows.firstOrNull { it.sessionId == selected }?.parentSessionId ?: selected
                val projection = operations.projection(lead)
                val profile = operations.profile()
                if (!current(operations, cut) || readNumber != number) return@launch
                if (lead == selected) selectedSubagents = projection.subagents
                mutable.update {
                    it.copy(
                        leadId = lead,
                        team = projection.team,
                        bundleAvailable = profile.available,
                        bundleEnabled = profile.enabled,
                    )
                }
                derive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentTeamOperationException) {
                if (current(operations, cut) && readNumber == number) fail(error.code)
            } finally {
                if (current(operations, cut) && readNumber == number) {
                    read = null
                    mutable.update { it.copy(loading = false) }
                    if (refreshQueued) {
                        refreshQueued = false
                        refresh()
                    }
                }
            }
        }
    }

    private fun armPoll() {
        poll?.cancel()
        poll = scope.launch {
            while (observed) {
                delay(3_000)
                refresh()
            }
        }
    }

    private fun loadMessages() {
        val operations = owner ?: return
        val team = mutable.value.team ?: return
        val lead = mutable.value.leadId ?: return
        if (messages?.isActive == true) return
        val cut = epoch
        messages = scope.launch {
            try {
                val result = operations.teamMessages(lead, team.members)
                if (current(operations, cut))
                    mutable.update {
                        it.copy(
                            messages = result.messages.toPersistentList(),
                            unreadable = result.unreadable.toPersistentSet(),
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private fun mutate(work: suspend (AgentTeamsRepository) -> Unit) {
        val operations = owner ?: return
        if (mutable.value.busy) return
        val cut = epoch
        mutable.update { it.copy(busy = true, error = null) }
        mutation = scope.launch {
            try {
                work(operations)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentTeamOperationException) {
                if (current(operations, cut)) fail(error.code)
            } finally {
                if (current(operations, cut)) {
                    mutable.update { it.copy(busy = false) }
                    refresh()
                }
            }
        }
    }

    private fun current(operations: AgentTeamsRepository, cut: Long) =
        operations === owner && repository.value === operations && cut == epoch

    private fun fail(code: String) {
        mutable.update { it.copy(error = failureMessage(code)) }
        onError(code)
    }
}

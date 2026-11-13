package dev.fughetta.dshcompose.ui.agents

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.collections.immutable.*

enum class AgentsTab {
    Lineage,
    Members,
    Tasks,
    Messages,
}

data class AgentsState(
    val connected: Boolean = false,
    val sessionId: String? = null,
    val leadId: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val tab: AgentsTab = AgentsTab.Lineage,
    val parent: SubagentAddress? = null,
    val nodes: PersistentList<AgentNode> = persistentListOf(),
    val team: AgentTeamSnapshot? = null,
    val messages: PersistentList<AgentTeamMessage> = persistentListOf(),
    val unreadable: PersistentSet<String> = persistentSetOf(),
    val running: PersistentSet<String> = persistentSetOf(),
    val bundleAvailable: Boolean = false,
    val bundleEnabled: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

sealed interface AgentsAction {
    data class Observe(val active: Boolean) : AgentsAction

    data class Tab(val value: AgentsTab) : AgentsAction

    data object Refresh : AgentsAction

    data object LoadMessages : AgentsAction

    data object EnableTeam : AgentsAction

    data class Open(val route: SubagentAddress) : AgentsAction

    data class OpenSession(val id: String) : AgentsAction

    data class Interrupt(val route: SubagentAddress) : AgentsAction
}

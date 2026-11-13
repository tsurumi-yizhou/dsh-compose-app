package dev.fughetta.dshcompose.domain.sessions

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class AgentNode(
    val address: SubagentAddress,
    val label: String,
    val title: String?,
    val depth: Int,
    val status: String,
    val elapsedMs: Long = 0,
    val createdAt: Long? = null,
)

data class AgentLineage(
    val nodes: List<AgentNode>,
    val parent: SubagentAddress?,
    val running: Set<String>,
)

class BuildAgentLineageUseCase {
    suspend operator fun invoke(
        sessions: List<SessionSummary>,
        selected: String,
        projection: SessionSubagents?,
    ): AgentLineage {
        val byId = sessions.associateBy { it.sessionId }
        val children =
            sessions
                .filter { it.origin == "subagent" && it.parentSessionId != null }
                .groupBy { it.parentSessionId }
        val nodes = mutableListOf<AgentNode>()
        val visited = mutableSetOf(selected)
        val context = currentCoroutineContext()
        fun walk(parent: String, depth: Int) {
            context.ensureActive()
            if (depth > 8) return
            val metadata =
                if (parent == selected && projection != null) projection
                else byId[parent]?.subagents
            val catalog = metadata?.catalog.orEmpty()
            val named = catalog.mapTo(mutableSetOf()) { it.id }
            val extra =
                children[parent]
                    .orEmpty()
                    .filter { it.sessionId !in named }
                    .sortedBy { it.updatedAt }
                    .map { SubagentCatalogEntry(it.sessionId) }
            (catalog + extra).forEach { entry ->
                val id = entry.id
                if (!visited.add(id)) return@forEach
                val row = byId[id]
                val identity = row?.subagents?.identity
                val mode = SubagentMode.parse(entry.mode ?: identity?.mode)
                val timing = row?.subagents?.timing
                val status =
                    if (row?.running == true) "running"
                    else
                        when (timing?.lastTurnCompleted) {
                            true -> "completed"
                            false -> "failed"
                            null -> "idle"
                        }
                nodes.add(
                    AgentNode(
                        SubagentAddress(parent, id, mode),
                        entry.label ?: identity?.label ?: row?.title ?: id,
                        row?.title,
                        depth,
                        status,
                        timing?.elapsedMs ?: 0,
                        entry.createdAt,
                    ),
                )
                if (mode != SubagentMode.External) walk(id, depth + 1)
            }
        }
        walk(selected, 0)
        val parent =
            byId[selected]
                ?.takeIf { it.origin == "subagent" }
                ?.parentSessionId
                ?.let {
                    SubagentAddress(
                        it,
                        selected,
                        SubagentMode.parse(byId[selected]?.subagents?.identity?.mode),
                    )
                }
        return AgentLineage(
            nodes,
            parent,
            sessions.filter { it.running }.mapTo(mutableSetOf()) { it.sessionId },
        )
    }
}

package dev.fughetta.dshcompose.domain.sessions

import kotlinx.collections.immutable.*

data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val cwd: String?,
    val running: Boolean,
    val blank: Boolean,
    val agentAvailable: Boolean,
    val parentSessionId: String?,
    val origin: String?,
    val updatedAt: Long,
    val subagents: SessionSubagents = SessionSubagents(),
    /** A local write is waiting for the engine; offline readers show it as unsynced. */
    val pendingWrite: Boolean = false,
)

data class SubagentCatalogEntry(
    val id: String,
    val label: String? = null,
    val mode: String? = null,
    val createdAt: Long? = null,
)

data class SubagentIdentity(val label: String?, val mode: String?)

data class SubagentTiming(
    val settledMs: Long = 0,
    val activeSince: Long? = null,
    val activeThrough: Long? = null,
    val lastTurnCompleted: Boolean? = null,
) {
    val elapsedMs
        get() =
            settledMs +
                if (activeSince != null && activeThrough != null)
                    (activeThrough - activeSince).coerceAtLeast(0)
                else 0
}

data class SessionSubagents(
    val catalog: PersistentList<SubagentCatalogEntry> = persistentListOf(),
    val identity: SubagentIdentity? = null,
    val timing: SubagentTiming? = null,
)

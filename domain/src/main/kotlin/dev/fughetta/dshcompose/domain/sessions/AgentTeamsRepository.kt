package dev.fughetta.dshcompose.domain.sessions

import kotlinx.coroutines.flow.Flow

data class AgentTeamProjection(val team: AgentTeamSnapshot?, val subagents: SessionSubagents?)

data class AgentTeamProfile(val available: Boolean, val enabled: Boolean)

sealed interface AgentTeamChange {
    data class SessionStatus(val id: String) : AgentTeamChange

    data object ProfileChanged : AgentTeamChange
}

class AgentTeamOperationException(val code: String, cause: Throwable? = null) :
    Exception(code, cause)

/** One connection generation; retired instances never serve a replacement connection. */
interface AgentTeamsRepository {
    suspend fun teamMessages(leadId: String, members: List<AgentTeamMember>): AgentTeamMessages

    val hostId: String

    val changes: Flow<AgentTeamChange>

    suspend fun projection(sessionId: String): AgentTeamProjection

    suspend fun profile(): AgentTeamProfile

    fun invalidateProfile()

    suspend fun messages(leadId: String, member: AgentTeamMember): List<AgentTeamMessage>

    suspend fun prepareOpen(address: SubagentAddress): SubagentAddress

    suspend fun interrupt(address: SubagentAddress)

    /** Returns whether the confirmed change requires an engine restart. */
    suspend fun enableProfile(): Boolean
}

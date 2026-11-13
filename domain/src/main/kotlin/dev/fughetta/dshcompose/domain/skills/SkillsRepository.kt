package dev.fughetta.dshcompose.domain.skills

import kotlinx.collections.immutable.PersistentList

/** One skill the host exposes for a conversation. */
data class SkillInfo(
    val name: String,
    val description: String,
    val whenToUse: String?,
    val path: String?,
    val modelInvocable: Boolean,
)

/** Skills available to one conversation on the host. */
interface SkillsRepository {
    suspend fun catalog(sessionId: String): SkillCatalog

    suspend fun createSession(
        sessions: dev.fughetta.dshcompose.domain.sessions.SessionsRepository,
        skill: SkillInfo,
        source: String,
        workspaces: List<dev.fughetta.dshcompose.domain.workspaces.Workspace>,
        selectedWorkspace: String?,
    ): SkillSessionCreation

    suspend fun list(sessionId: String): PersistentList<SkillInfo>
}

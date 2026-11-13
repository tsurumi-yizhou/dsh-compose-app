package dev.fughetta.dshcompose.data.skills

import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.domain.skills.*
import dev.fughetta.dshcompose.domain.skills.SkillsRepository
import dev.fughetta.dshcompose.domain.skills.validSkillName
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** SkillsRepository backed by one engine connection. */
class EngineSkillsRepository(
    private val client: ProtocolClient,
    private val connectionActive: () -> Boolean = { true },
) : SkillsRepository {
    private val connectionGeneration = client.mux.state.value.generation

    override suspend fun catalog(sessionId: String): SkillCatalog {
        val skills = list(sessionId)
        return withContext(Dispatchers.Default) { SkillCatalog(skills) }
    }

    override suspend fun createSession(
        sessions: dev.fughetta.dshcompose.domain.sessions.SessionsRepository,
        skill: SkillInfo,
        source: String,
        workspaces: List<dev.fughetta.dshcompose.domain.workspaces.Workspace>,
        selectedWorkspace: String?,
    ): SkillSessionCreation =
        createSkillSession(
            SkillsPorts(this, sessions),
            skill,
            source,
            workspaces,
            selectedWorkspace,
        )

    override suspend fun list(sessionId: String): PersistentList<SkillInfo> =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                val result =
                    client.connection
                        .call(
                            "skills/list",
                            buildJsonObject {
                                putJsonObject("request") { put("sessionId", sessionId) }
                            },
                        )
                        .objectValue()
                val rows =
                    result["skills"] as? JsonArray ?: throw HostProtocolException("Missing skills")
                rows
                    .mapNotNull { raw ->
                        val value = raw as? JsonObject ?: return@mapNotNull null
                        val name =
                            value.string("name")?.takeIf(::validSkillName) ?: return@mapNotNull null
                        SkillInfo(
                            name,
                            value.string("description").orEmpty(),
                            value.string("whenToUse"),
                            value.string("path"),
                            value.boolean("modelInvocable") != false,
                        )
                    }
                    .distinctBy { it.name }
                    .sortedBy { it.name }
                    .toPersistentList()
            }
        }
}

package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun readAgentTeamMessages(
    operations: AgentTeamsRepository,
    leadId: String,
    members: List<AgentTeamMember>,
): AgentTeamMessages = coroutineScope {
    val gate = Semaphore(3)
    val result =
        members
            .map { member ->
                async {
                    gate.withPermit {
                        try {
                            operations.messages(leadId, member) to null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: AgentTeamOperationException) {
                            emptyList<AgentTeamMessage>() to member.id
                        }
                    }
                }
            }
            .awaitAll()
    mergeAgentTeamMessages(
        result.flatMap { it.first },
        result.mapNotNull { it.second }.toSet(),
    )
}

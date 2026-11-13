package dev.fughetta.dshcompose.domain.sessions

data class AgentTeamMessages(val messages: List<AgentTeamMessage>, val unreadable: Set<String>)

fun mergeAgentTeamMessages(messages: List<AgentTeamMessage>, unreadable: Set<String>) =
    AgentTeamMessages(messages.sortedByDescending { it.time }.take(200), unreadable)

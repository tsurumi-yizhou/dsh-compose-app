package dev.fughetta.dshcompose.domain.sessions

data class AgentTeamMember(
    val id: String,
    val name: String,
    val lead: Boolean,
    val phase: String,
    val error: String?,
)

data class AgentTeamTask(
    val id: String,
    val subject: String,
    val description: String,
    val status: String,
    val owner: String?,
    val ready: Boolean,
    val blockedBy: List<String>,
    val scopes: List<String>,
    val warnings: List<String>,
)

data class AgentTeamSnapshot(
    val members: List<AgentTeamMember>,
    val tasks: List<AgentTeamTask>,
    val failure: String?,
)

data class AgentTeamMessage(
    val recipient: String,
    val sender: String?,
    val senderName: String?,
    val text: String,
    val time: Long,
    val seq: Long,
)

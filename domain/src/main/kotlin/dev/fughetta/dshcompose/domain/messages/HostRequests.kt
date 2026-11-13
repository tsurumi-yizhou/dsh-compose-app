package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration

data class ApprovalRequest(
    val eventId: String,
    val sessionId: String,
    val toolName: String,
    val reason: String?,
    val displayReason: Map<String, String>,
    val callId: String?,
)

data class HostRequests(
    val generation: ConnectionGeneration?,
    val pending: Map<String, ToolPayload.Obj>,
    val questions: Map<String, UserQuestionRequest>,
    val approvals: Map<String, ApprovalRequest>,
    val error: String? = null,
)

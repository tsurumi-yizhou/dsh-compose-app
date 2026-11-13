package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

internal fun hostRequests(
    generation: ConnectionGeneration?,
    pending: Map<String, JsonObject>,
): HostRequests {
    val questions = mutableMapOf<String, UserQuestionRequest>()
    val approvals = mutableMapOf<String, ApprovalRequest>()
    var error: String? = null
    for ((id, event) in pending) {
        when (event.string("event")) {
            "user-questions/request" ->
                try {
                    questions[id] = decodeQuestionRequest(event)
                } catch (failure: HostException) {
                    error = failure.message
                }
            "approval/request" -> {
                val request = event["request"] as? JsonObject ?: continue
                val session = event.string("agentId") ?: continue
                approvals[id] =
                    ApprovalRequest(
                        id,
                        session,
                        request.string("toolName").orEmpty(),
                        request.string("reason"),
                        (request["displayReason"] as? JsonObject)
                            .orEmpty()
                            .mapNotNull { (key, value) ->
                                val text = value as? JsonPrimitive
                                if (text?.isString == true) key to text.content else null
                            }
                            .toMap(),
                        request.string("callId"),
                    )
            }
        }
    }
    return HostRequests(
        generation,
        pending.mapValues { it.value.toToolPayload() as ToolPayload.Obj },
        questions,
        approvals,
        error,
    )
}

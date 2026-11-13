package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

private fun QuestionAnswer.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("selected", JsonArray(selected.map(::JsonPrimitive)))
    custom?.let { put("custom", it) }
}

suspend fun ProtocolClient.answerQuestion(
    eventId: String,
    answers: List<QuestionAnswer>,
    generation: ConnectionGeneration,
) {
    mux.respondHostEvent(
        eventId,
        buildJsonObject {
            put("kind", "result")
            putJsonObject("value") { put("answers", JsonArray(answers.map { it.toJson() })) }
        },
        expectedGeneration = generation,
    )
}

suspend fun ProtocolClient.rejectQuestion(eventId: String, generation: ConnectionGeneration) {
    mux.respondHostEvent(
        eventId,
        buildJsonObject {
            put("kind", "rejected")
            putJsonObject("error") {
                put("name", "UserQuestionError")
                put("message", "declined")
            }
        },
        expectedGeneration = generation,
    )
}

fun ProtocolClient.claimQuestionWait(sessionId: String, callId: String): Flow<MuxEvent> =
    mux.stream(
        "userQuestions/attachWait",
        buildJsonObject {
            put("agentId", sessionId)
            put("callId", callId)
        },
    )

suspend fun ProtocolClient.answerContinuedQuestion(
    sessionId: String,
    callId: String,
    answers: List<QuestionAnswer>,
): Boolean {
    val value =
        connection.call(
            "userQuestions/answer",
            buildJsonObject {
                put("agentId", sessionId)
                put("callId", callId)
                putJsonObject("answer") { put("answers", JsonArray(answers.map { it.toJson() })) }
            },
        )
    return (value as? JsonPrimitive)?.booleanOrNull
        ?: throw HostProtocolException("Invalid continued question result")
}

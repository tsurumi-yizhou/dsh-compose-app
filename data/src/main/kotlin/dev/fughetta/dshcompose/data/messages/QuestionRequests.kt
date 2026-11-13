package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*

fun decodeQuestionRequest(event: JsonObject): UserQuestionRequest {
    val request =
        event["request"]?.objectValue() ?: throw HostProtocolException("Missing question request")
    val wait = request["wait"] as? JsonObject
    return UserQuestionRequest(
        event.requiredString("eventId"),
        event.requiredString("agentId"),
        (request["questions"] as? JsonArray).orEmpty().map { value ->
            val question = value.objectValue()
            UserQuestion(
                question.requiredString("id"),
                question.requiredString("question"),
                question.string("detail"),
                question.string("header"),
                (question["options"] as? JsonArray).orEmpty().map { option ->
                    val obj = option.objectValue()
                    QuestionOption(obj.requiredString("label"), obj.string("description"))
                },
                question.boolean("multiSelect") == true,
                (question["intent"] as? JsonObject)?.toToolPayload(),
            )
        },
        wait?.string("callId"),
        wait?.boolean("timed") == true,
    )
}

fun decodeQuestionRequest(
    event: dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
): UserQuestionRequest = decodeQuestionRequest(event.toJsonElement() as JsonObject)

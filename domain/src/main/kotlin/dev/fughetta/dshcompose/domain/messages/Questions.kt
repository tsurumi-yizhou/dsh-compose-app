package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.chat.ToolPayload

data class QuestionOption(val label: String, val description: String?)

data class UserQuestion(
    val id: String,
    val question: String,
    val detail: String?,
    val header: String?,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
    val intent: ToolPayload?,
)

data class QuestionAnswer(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

data class UserQuestionRequest(
    val eventId: String,
    val sessionId: String,
    val questions: List<UserQuestion>,
    val waitCallId: String?,
    val timed: Boolean,
)

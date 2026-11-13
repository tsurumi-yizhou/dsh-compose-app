package dev.fughetta.dshcompose.domain.chat

import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.messages.TranscriptNoticeData

enum class ChatRole {
    User,
    Assistant,
    Tool,
    Notice,
}

enum class ToolState {
    Streaming,
    Running,
    AwaitingApproval,
    Succeeded,
    Failed,
    Denied,
    Cancelled,
}

data class ToolPresentation(
    val callId: String,
    val name: String,
    val arguments: String,
    val state: ToolState,
    val failure: ToolPayload? = null,
    val meta: ToolPayload? = null,
)

data class CommandPresentation(
    val id: String,
    val line: String,
    val done: Boolean,
    val success: Boolean,
)

data class ChatItem(
    val key: String,
    val role: ChatRole,
    val text: String = "",
    val reasoning: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val seq: Long? = null,
    val streaming: Boolean = false,
    val interrupted: Boolean = false,
    val tool: ToolPresentation? = null,
    val requestId: String? = null,
    val noticeKind: String? = null,
    val command: CommandPresentation? = null,
    val turnId: String? = null,
    val messageId: String? = null,
    val notice: TranscriptNoticeData? = null,
)

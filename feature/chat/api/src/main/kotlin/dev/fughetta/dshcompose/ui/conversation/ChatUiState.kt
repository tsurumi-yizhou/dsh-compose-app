package dev.fughetta.dshcompose.ui.conversation

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.InboxItem
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.TodoList
import dev.fughetta.dshcompose.domain.transcript.ChatMessageOrder
import dev.fughetta.dshcompose.domain.transcript.TranscriptTurnIndex
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import kotlinx.collections.immutable.*

data class CommandReply(
    val line: String,
    val result: dev.fughetta.dshcompose.domain.commands.CommandResult,
)

data class ChatUiState(
    // Check the revision before collection equality can scan the loaded history.
    val messageRevision: Long = 0,
    val historyEpoch: Long = 0,
    val pendingPrompts: dev.fughetta.dshcompose.ui.messages.PendingPromptsUiState =
        dev.fughetta.dshcompose.ui.messages.PendingPromptsUiState(),
    val foldCompletedTurns: Boolean = true,
    val title: String? = null,
    val origin: String? = null,
    val goal: dev.fughetta.dshcompose.domain.goals.GoalProjection? = null,
    val todos: dev.fughetta.dshcompose.domain.sessions.TodoList? = null,
    val lastCommand: CommandReply? = null,
    val model: ModelSelection? = null,
    /** The session's agent preset; on the draft, the one it will be created with. */
    val agentPreset: String? = null,
    val defaultModelSaveFailed: Boolean = false,
    val messages: PersistentList<ChatItem> = persistentListOf(),
    val mediaRevision: Long = 0,
    val generatedMedia:
        PersistentMap<String, dev.fughetta.dshcompose.domain.media.MediaTranscriptItem> =
        persistentMapOf(),
    val generatedMediaPaths: PersistentSet<String> = persistentSetOf(),
    val messageOrder: ChatMessageOrder = ChatMessageOrder.Empty,
    val turns: dev.fughetta.dshcompose.domain.transcript.TranscriptTurnIndex =
        dev.fughetta.dshcompose.domain.transcript.TranscriptTurnIndex.Empty,
    val inbox: PersistentList<dev.fughetta.dshcompose.domain.messages.InboxItem> =
        persistentListOf(),
    val viewReply: ViewReplyUiState? = null,
    val quote: String? = null,
    val forkSourceId: String? = null,
    val forkWarning: String? = null,
    val draft: String = "",
    val draftCursor: Int? = null,
    val draftSelectionEnd: Int? = null,
    val draftSpans: PersistentList<ReferenceSpan> = persistentListOf(),
    val attachments: List<ComposerAttachment> = emptyList(),
    val approvals: List<ApprovalUiState> = emptyList(),
    val questions: List<QuestionUiState> = emptyList(),
    val readOnly: Boolean = false,
    val syncing: Boolean = true,
    val sending: Boolean = false,
    val running: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val loadingHistory: Boolean = false,
    val error: String? = null,
)

data class ViewReplyUiState(val messageKey: String, val text: String, val busy: Boolean)

package dev.fughetta.dshcompose.domain.sessions

import dev.fughetta.dshcompose.domain.goals.GoalProjection
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.InboxItem
import dev.fughetta.dshcompose.domain.providers.ModelSelection
import dev.fughetta.dshcompose.domain.transcript.ChatPresentation
import kotlinx.collections.immutable.PersistentList

data class TranscriptSnapshot(
    val generation: ConnectionGeneration?,
    val revision: Long,
    val historyEpoch: Long,
    val presentation: ChatPresentation,
    val model: ModelSelection?,
    val agentPreset: String?,
    val inbox: PersistentList<InboxItem>,
    val title: String?,
    val goal: GoalProjection?,
    val todos: TodoList?,
    val origin: String?,
    val running: Boolean,
    val hasMoreHistory: Boolean,
    val syncing: Boolean,
    val summary: SessionSummary?,
    val permissionChanged: Boolean,
)

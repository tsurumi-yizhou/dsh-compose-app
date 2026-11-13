package dev.fughetta.dshcompose.domain.sessions

import dev.fughetta.dshcompose.domain.commands.CommandResult
import dev.fughetta.dshcompose.domain.messages.TranscriptRetryPlan
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.usage.UsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One conversation and its connection-bound transcript. Replacing the transcript retires in-flight
 * pages and every operation issued against the previous publication.
 */
interface ConversationRepository : AutoCloseable {
    val historyEpoch: StateFlow<Long>

    fun publications(
        isCurrentConnection: () -> Boolean,
        usage: UsageRepository? = null,
    ): Flow<TranscriptSnapshot>

    /** Updates the conversation publication; failures use SessionOperationException codes. */
    suspend fun loadHistory()

    /** Apply values already acknowledged by the host to the conversation's publication. */
    suspend fun confirmModel(selection: ModelSelection)

    suspend fun confirmAgentPreset(preset: String)

    suspend fun confirmCommand(result: CommandResult, line: String)

    suspend fun pendingApprovals(callIds: Set<String>)

    suspend fun retryForNotice(key: String): TranscriptRetryPlan?

    suspend fun branchPointAfter(seq: Long): Long?

    suspend fun branchPointBefore(seq: Long): Long?
}

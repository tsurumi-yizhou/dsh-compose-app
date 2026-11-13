package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import kotlinx.collections.immutable.*

enum class PromptMode {
    Queue,
    Steer,
}

data class PendingPromptsOwner(val host: String, val home: String, val sessionId: String)

enum class PendingPromptState {
    Sending,
    Uncertain,
    Queued,
    Rejected,
    Recovered,
}

data class PendingPromptAttempt(
    val owner: PendingPromptsOwner,
    val requestId: String,
    val number: Long,
)

data class PendingPrompt(
    val requestId: String,
    val host: String,
    val home: String,
    val sessionId: String,
    val text: String,
    val quote: String?,
    val composerText: String,
    val files: PersistentList<ComposerFile>,
    val state: PendingPromptState = PendingPromptState.Sending,
    val itemId: String? = null,
    val attempt: Long = 1,
    val errorCode: String? = null,
    val composerOwned: Boolean = true,
    val mode: PromptMode = PromptMode.Queue,
    val timeZone: String? = null,
    val fileReceiptIds: PersistentList<String> = persistentListOf(),
) {
    val owner
        get() = PendingPromptsOwner(host, home, sessionId)

    val token
        get() = PendingPromptAttempt(owner, requestId, attempt)
}

enum class PendingPromptFold {
    Retained,
    Consumed,
    Withdrawn,
    Stale,
}

data class RecoveredPromptContent(val text: String, val quote: String?)

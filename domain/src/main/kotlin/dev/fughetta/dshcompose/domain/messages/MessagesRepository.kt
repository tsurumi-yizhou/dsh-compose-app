package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface MessagesRepository : AutoCloseable {
    val translation: StateFlow<ReasoningTranslation?>

    val delivered: SharedFlow<DeliveredPrompt>

    fun persist(host: String, home: String)

    suspend fun restore(host: String, home: String?): List<PendingPrompt>

    fun resume()

    suspend fun retryDelivery(target: PendingPromptsOwner, requestId: String)

    val state: StateFlow<PersistentMap<String, PendingPrompt>>

    fun begin(
        host: String,
        home: String,
        sessionId: String,
        text: String,
        composerText: String,
        quote: String?,
        files: List<ComposerFile>,
        mode: PromptMode = PromptMode.Queue,
        timeZone: String? = null,
        fileReceiptIds: List<String> = emptyList(),
        composerOwned: Boolean = true,
    ): PendingPrompt

    fun retry(owner: PendingPromptsOwner, requestId: String): PendingPrompt

    /**
     * Re-admits a prompt restored from disk. Never overwrites a newer in-memory entry and never
     * revives one the engine already confirmed; returns whether the prompt is now pending.
     */
    fun restore(prompt: PendingPrompt): Boolean

    fun acknowledged(owner: PendingPromptsOwner, requestId: String): PendingPromptFold

    fun failed(token: PendingPromptAttempt, error: Exception): PendingPromptFold

    fun rejected(token: PendingPromptAttempt, code: String): PendingPromptFold

    fun connectionLost(host: String, home: String)

    fun retainedFileIds(): Set<String>

    fun find(owner: PendingPromptsOwner, requestId: String): PendingPrompt?

    fun byItem(owner: PendingPromptsOwner, itemId: String): PendingPrompt?

    fun withdrawn(
        owner: PendingPromptsOwner,
        itemId: String,
        restore: Boolean,
        content: RecoveredPromptContent? = null,
    ): PendingPrompt?

    fun retainWithdrawnText(
        owner: PendingPromptsOwner,
        rawText: String,
        content: RecoveredPromptContent,
    ): PendingPrompt

    /** Called only after the engine confirms deletion of this exact conversation. */
    fun sessionDeleted(owner: PendingPromptsOwner)

    fun recoverDraft(owner: PendingPromptsOwner, requestId: String): PendingPrompt?

    fun wasConsumed(owner: PendingPromptsOwner, requestId: String): Boolean
}

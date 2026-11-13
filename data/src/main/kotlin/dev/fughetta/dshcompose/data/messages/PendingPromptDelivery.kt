package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.data.sync.PendingPromptOutbox
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.HostSelection
import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Restores and retries durable prompts against the selected host, independently of navigation. */
class PendingPromptDelivery(
    parent: CoroutineScope,
    private val connection: HostSelection,
    private val pending: MessagesRepository,
    private val outbox: PendingPromptOutbox,
) : AutoCloseable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val submit = SubmitPendingPromptUseCase(pending)
    private val mutableDelivered = MutableSharedFlow<DeliveredPrompt>(extraBufferCapacity = 8)
    val delivered = mutableDelivered.asSharedFlow()
    private var resumedFor: ConnectionGeneration? = null
    private var resuming: Job? = null

    fun persist(host: String, home: String) {
        val prompts =
            pending.state.value.values.filter {
                it.host == host && it.home == home && it.state == PendingPromptState.Uncertain
            }
        if (prompts.isEmpty()) return
        scope.launch { prompts.forEach { outbox.retain(it) } }
    }

    suspend fun restore(host: String, home: String?): List<PendingPrompt> {
        val restored =
            try {
                outbox.outstanding(host).filter { home == null || it.home == home }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        restored.forEach { pending.restore(it) }
        resume()
        return restored
    }

    fun resume() {
        val owner = connection.session ?: return
        val generation = connection.state.value.generation ?: return
        if (resumedFor == generation) return
        val uncertain =
            pending.state.value.values.filter {
                it.host == owner.hostId &&
                    it.home == generation.home &&
                    it.state == PendingPromptState.Uncertain
            }
        if (uncertain.isEmpty()) return
        resumedFor = generation
        resuming?.cancel()
        resuming = scope.launch {
            for (prompt in uncertain) {
                try {
                    retry(prompt.owner, prompt.requestId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The ledger retains the rejection/uncertain state for the UI and next retry.
                }
            }
        }
    }

    suspend fun retry(target: PendingPromptsOwner, requestId: String) {
        val owner = connection.session ?: return
        val generation = connection.state.value.generation ?: return
        fun current() =
            connection.session === owner &&
                owner.hostId == target.host &&
                generation.home == target.home &&
                connection.state.value.generation == generation
        if (!current()) return
        val prompt = pending.retry(target, requestId)
        try {
            val submission = owner.messageAttachments.prepareRetry(prompt)
            if (!current()) throw CancellationException("Pending prompt connection retired")
            if (!pending.wasConsumed(target, prompt.requestId)) submit(prompt, submission)
            outbox.settle(prompt.host, prompt.requestId)
            if (current() && prompt.composerOwned)
                mutableDelivered.emit(DeliveredPrompt(prompt, generation))
        } catch (error: Exception) {
            val fold = pending.failed(prompt.token, error)
            if (pending.find(target, requestId)?.state == PendingPromptState.Rejected)
                outbox.rejected(target.host, requestId)
            if (error is CancellationException || fold != PendingPromptFold.Consumed) throw error
        }
    }

    override fun close() {
        lifetime.cancel()
    }
}

package dev.fughetta.dshcompose.domain.messages

import java.util.concurrent.CancellationException

sealed interface PromptSubmissionResult {
    data object Acknowledged : PromptSubmissionResult

    data class Failed(val error: Exception, val rejectionCode: String? = null) :
        PromptSubmissionResult
}

/** A submission binds the connection and prepared payload before the operation starts. */
typealias PendingPromptSubmission = suspend (PendingPrompt) -> PromptSubmissionResult

/** Engine proofs settle delivery even when an earlier transport attempt completes late. */
class SubmitPendingPromptUseCase(private val repository: MessagesRepository) {
    suspend operator fun invoke(
        pending: PendingPrompt,
        submission: PendingPromptSubmission,
    ): Boolean {
        return when (val outcome = submission(pending)) {
            PromptSubmissionResult.Acknowledged -> {
                val result = repository.acknowledged(pending.owner, pending.requestId)
                result != PendingPromptFold.Withdrawn && result != PendingPromptFold.Stale
            }
            is PromptSubmissionResult.Failed -> {
                val result =
                    outcome.rejectionCode?.let { repository.rejected(pending.token, it) }
                        ?: repository.failed(pending.token, outcome.error)
                if (result == PendingPromptFold.Consumed && outcome.error !is CancellationException)
                    true
                else throw outcome.error
            }
        }
    }
}

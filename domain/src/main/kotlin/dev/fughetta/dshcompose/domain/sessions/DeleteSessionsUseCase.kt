package dev.fughetta.dshcompose.domain.sessions

import kotlinx.coroutines.CancellationException

data class SessionDeletionResult(
    val deleted: List<String> = emptyList(),
    val failures: Map<String, String> = emptyMap(),
    val retired: Boolean = false,
    val cleanupFailures: Map<String, String> = emptyMap(),
)

/** Acknowledged deletions and subsequent local cleanup have separate outcomes. */
class DeleteSessionsUseCase(private val repository: SessionsRepository) {
    suspend operator fun invoke(
        ids: List<String>,
        deleting: (String) -> Unit = {},
        confirmed: suspend (String) -> Unit,
    ): SessionDeletionResult {
        val unique = ids.distinct()
        val deleted = mutableListOf<String>()
        val failures = linkedMapOf<String, String>()
        val cleanupFailures = linkedMapOf<String, String>()
        for ((index, id) in unique.withIndex()) {
            if (!repository.active) break
            deleting(id)
            try {
                repository.delete(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!repository.active) break
                val code = (error as? SessionOperationException)?.code ?: "failed"
                failures[id] = code
                if (code == "connection-lost" || code == "protocol-error") {
                    unique.drop(index + 1).forEach { failures[it] = "not-attempted" }
                    break
                }
                continue
            }
            if (!repository.active) break
            deleted += id
            try {
                confirmed(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                cleanupFailures[id] = "cleanup-failed"
            }
        }
        return SessionDeletionResult(
            deleted.toList(),
            failures.toMap(),
            retired = !repository.active,
            cleanupFailures = cleanupFailures.toMap(),
        )
    }
}

package dev.fughetta.dshcompose.domain.sessions

import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SessionForkState(val title: String?, val queuedItemIds: List<String?>)

data class ForkOutcome(
    val sessionId: String,
    val title: String?,
    val leftQueued: Int,
    val cleanupUnknown: Boolean,
    val warnings: Set<String>,
)

/** Acknowledged forks survive subsequent queue cleanup or title failures. */
class ForkSessionUseCase(private val repository: SessionsRepository) {
    suspend operator fun invoke(
        sessionId: String,
        atSeq: Long,
        sourceTitle: String? = null,
    ): ForkOutcome {
        suspend fun requireActive() {
            currentCoroutineContext().ensureActive()
            if (!repository.active) throw SessionOperationException("connection-lost")
        }
        requireActive()
        val child = repository.forkSession(sessionId, atSeq)
        val warnings = mutableSetOf<String>()
        val state =
            try {
                requireActive()
                repository.forkState(child)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        if (state == null) warnings.add("fork/projections-unavailable")
        var left = 0
        for (id in state?.queuedItemIds.orEmpty()) {
            if (id == null) {
                left++
                continue
            }
            try {
                requireActive()
                repository.updateQueuedPrompt(child, id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionOperationException) {
                if (error.code != "session/queue-item-not-found") left++
            } catch (_: Exception) {
                left++
            }
        }
        if (left > 0) warnings.add("fork/queue-cleanup-incomplete")
        var title: String? = null
        val inherited = state?.title
        if (!inherited.isNullOrBlank()) {
            try {
                requireActive()
                title =
                    repository.rename(
                        child,
                        bumpedForkTitle(sourceTitle?.takeIf(String::isNotBlank) ?: inherited),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                warnings.add("fork/rename-failed")
            }
        }
        return ForkOutcome(child, title, left, state == null, warnings.toSet())
    }
}

fun bumpedForkTitle(title: String): String {
    for ((pattern, open, close) in
        listOf(
            Triple(Regex("^(.*?)\\((\\d+)\\)$"), "(", ")"),
            Triple(Regex("^(.*?)（(\\d+)）$"), "（", "）"),
        )) {
        pattern.matchEntire(title)?.let {
            return it.groupValues[1] +
                open +
                (it.groupValues[2].toBigInteger() + BigInteger.ONE) +
                close
        }
    }
    return "$title (1)"
}

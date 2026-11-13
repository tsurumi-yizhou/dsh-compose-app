package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.coroutines.*

suspend fun <T : AutoCloseable, R> transferSessionArchive(
    owner: SessionsRepository,
    session: String,
    destination: suspend () -> T,
    output: (T) -> dev.fughetta.dshcompose.domain.ByteSink,
    commit: suspend (T) -> R,
    current: () -> Boolean,
    progress: (Long, Long?) -> Unit,
): SessionArchiveReceipt<R>? {
    val caller = currentCoroutineContext().minusKey(Job)
    var target: T? = null
    try {
        var lastProgress = 0L
        val bytes =
            withContext(Dispatchers.IO) {
                val opened = destination().also { target = it }
                owner.exportLog(session, output(opened)) { bytes, total ->
                    val now = System.nanoTime()
                    if (bytes == 0L || now - lastProgress >= 64_000_000) {
                        lastProgress = now
                        withContext(caller) { if (current()) progress(bytes, total) }
                    }
                }
            }
        if (!current()) return null
        val result =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                commit(checkNotNull(target))
            }
        return SessionArchiveReceipt(bytes, result)
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                target?.close()
            } catch (_: Exception) {}
        }
    }
}

package dev.fughetta.dshcompose.data.terminal

import dev.fughetta.dshcompose.protocol.ConnectionClosedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Retain acknowledgements belong to their original request, including when an id is reused. */
class TerminalRetention(private val scope: CoroutineScope) : AutoCloseable {
    private val jobs = mutableMapOf<String, Job>()
    private val acknowledgements = mutableMapOf<String, CompletableDeferred<Unit>>()

    fun hold(
        id: String,
        retain: () -> Flow<Unit>,
        current: () -> Boolean,
        onFailure: (Exception) -> Unit,
    ) {
        if (jobs[id]?.isActive == true) return
        val ack = CompletableDeferred<Unit>()
        acknowledgements[id] = ack
        jobs[id] = scope.launch {
            try {
                retain().collect { if (current() && !ack.isCompleted) ack.complete(Unit) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!ack.isCompleted) ack.completeExceptionally(error)
                if (current()) onFailure(error)
            } finally {
                if (!ack.isCompleted) ack.cancel()
            }
        }
    }

    suspend fun await(id: String) {
        val ack = acknowledgements[id] ?: throw ConnectionClosedException()
        withTimeout(20000) { ack.await() }
    }

    fun keep(ids: Set<String>) {
        jobs.keys.filter { it !in ids }.forEach(::release)
    }

    fun release(id: String) {
        jobs.remove(id)?.cancel()
        acknowledgements.remove(id)?.cancel()
    }

    override fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        acknowledgements.values.forEach { it.cancel() }
        acknowledgements.clear()
    }
}

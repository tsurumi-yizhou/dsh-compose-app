package dev.fughetta.dshcompose.phoneui

import kotlinx.coroutines.*

/** Main-thread handoff of the same phone return to notification delivery, never a second finish. */
class PhoneTurnResults(parentScope: CoroutineScope, private val capacity: Int = 128) :
    AutoCloseable {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private var connection: Any? = null
    private var generation: Any? = null
    private var serial = 0L
    private val runs = mutableMapOf<String, Long>()

    private class Entry(val serial: Long, val result: Deferred<PhoneTurnFinish?>)

    private val results = linkedMapOf<String, Entry>()
    private var closed = false

    fun connection(connection: Any?, generation: Any?) {
        if (this.connection === connection && this.generation == generation) return
        results.values.forEach { it.result.cancel() }
        results.clear()
        runs.clear()
        this.connection = connection
        this.generation = generation
    }

    fun started(connection: Any?, generation: Any?, sessionId: String) {
        if (!current(connection, generation)) return
        runs[sessionId] = ++serial
        results.remove(sessionId)?.result?.cancel()
    }

    fun finished(
        connection: Any?,
        generation: Any?,
        sessionId: String,
        finish: suspend () -> PhoneTurnFinish?,
    ): Deferred<PhoneTurnFinish?>? {
        if (!current(connection, generation)) return null
        val run =
            runs.remove(sessionId)
                ?: results[sessionId]?.let {
                    return it.result
                }
                ?: ++serial
        results.remove(sessionId)?.result?.cancel()
        val result = scope.async(start = CoroutineStart.UNDISPATCHED) { finish() }
        results[sessionId] = Entry(run, result)
        while (results.size > capacity) {
            val key =
                results.entries.firstOrNull { it.value.result.isCompleted }?.key
                    ?: results.keys.first()
            results.remove(key)?.result?.cancel()
        }
        return result
    }

    /** Captures the completed run before suspending; a new run cannot substitute its result. */
    suspend fun await(
        connection: Any?,
        generation: Any?,
        sessionId: String,
    ): PhoneTurnFinish? {
        if (!current(connection, generation) || sessionId in runs) return null
        val entry = results[sessionId] ?: return null
        val result =
            try {
                entry.result.await()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                return null
            }
        return result.takeIf {
            current(connection, generation) &&
                results[sessionId]?.serial == entry.serial &&
                sessionId !in runs
        }
    }

    private fun current(connection: Any?, generation: Any?) =
        !closed && this.connection === connection && this.generation == generation

    override fun close() {
        closed = true
        connection(null, null)
        lifetime.cancel()
    }
}

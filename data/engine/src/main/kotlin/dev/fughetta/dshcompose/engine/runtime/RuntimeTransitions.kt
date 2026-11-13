package dev.fughetta.dshcompose.engine.runtime

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Waits for runtime status edges; selecting a host never stands in for process readiness. */
class RuntimeTransitions(private val runtime: RuntimeController) : AutoCloseable {
    private val statusRevision = MutableStateFlow(0L)
    private val sink = RuntimeController.EventSink { event ->
        if (event["type"] == "status") statusRevision.update { it + 1 }
    }

    init {
        runtime.addSink(sink)
    }

    suspend fun stop() {
        if (
            runtime.phase in
                setOf(
                    RuntimeController.Phase.RUNNING,
                    RuntimeController.Phase.STARTING,
                    RuntimeController.Phase.STOPPING,
                )
        ) {
            runtime.stop()
            // A real native status edge ends the wait. Never infer completion from the selected
            // Host.
            withTimeout(45_000) {
                statusRevision.first {
                    runtime.phase !in
                        setOf(
                            RuntimeController.Phase.RUNNING,
                            RuntimeController.Phase.STARTING,
                            RuntimeController.Phase.STOPPING,
                        )
                }
            }
        }
    }

    suspend fun start(phoneStorage: Boolean) {
        check(
            runtime.phase !in
                setOf(
                    RuntimeController.Phase.NOT_INSTALLED,
                    RuntimeController.Phase.INSTALLING,
                    RuntimeController.Phase.STOPPING,
                ),
        ) {
            "engine/not-ready"
        }
        if (runtime.phase == RuntimeController.Phase.RUNNING) return
        val before = statusRevision.value
        runtime.start(emptyMap(), phoneStorage = phoneStorage)
        withTimeout(120_000) {
            statusRevision.first { revision ->
                revision > before &&
                    runtime.phase in
                        setOf(RuntimeController.Phase.RUNNING, RuntimeController.Phase.ERROR)
            }
        }
        check(runtime.phase == RuntimeController.Phase.RUNNING) { "engine/start-failed" }
    }

    suspend fun reset(keep: Set<ResetKeep>) =
        suspendCancellableCoroutine<Unit> { continuation ->
            runtime.reset(keep) { problem ->
                if (continuation.isActive) {
                    if (problem == null) continuation.resume(Unit)
                    else continuation.resumeWithException(problem)
                }
            }
        }

    suspend fun restore(phoneStorage: Boolean) {
        stop()
        suspendCancellableCoroutine<Unit> { continuation ->
            runtime.restoreLastKnownGood { _, problem ->
                if (continuation.isActive) {
                    if (problem == null) continuation.resume(Unit)
                    else continuation.resumeWithException(problem)
                }
            }
        }
        start(phoneStorage)
    }

    override fun close() {
        runtime.removeSink(sink)
    }
}

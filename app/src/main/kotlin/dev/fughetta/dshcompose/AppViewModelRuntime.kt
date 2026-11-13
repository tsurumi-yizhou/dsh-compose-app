package dev.fughetta.dshcompose

import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.connection.status
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal suspend fun AppViewModel.restartForPhonePreferences(): Unit = restartLocalRuntime()

internal suspend fun AppViewModel.restartLocalRuntime(): Unit = runtimeRestart.withLock {
    if (runtime.phase != RuntimeController.Phase.RUNNING)
        throw OperationFailure("engine/restart-unavailable")
    val stopped = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    var startRequested = false
    val listener = RuntimeController.EventSink { event ->
        if (event["type"] == "status") {
            val status = event["status"] as? Map<*, *>
            if (status?.get("phase") in setOf("installed", "error", "not-installed"))
                stopped.complete(Unit)
            if (startRequested && status?.get("phase") == "running") started.complete(Unit)
            if (startRequested && status?.get("phase") == "error")
                started.completeExceptionally(OperationFailure("engine/restart-failed"))
        }
    }
    runtime.addSink(listener)
    try {
        runtime.stop()
        withTimeout(30000) { stopped.await() }
        currentCoroutineContext().ensureActive()
        check(
            runtime.phase == RuntimeController.Phase.INSTALLED ||
                runtime.phase == RuntimeController.Phase.ERROR,
        ) {
            "Engine is not ready to restart"
        }
        startRequested = true
        runtime.start(
            emptyMap(),
            phoneStorage = phoneControls.phoneStorageEnabled,
        )
        withTimeout(45_000) { started.await() }
    } finally {
        runtime.removeSink(listener)
    }
}

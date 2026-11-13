package dev.fughetta.dshcompose.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import dev.fughetta.dshcompose.protocol.HostConnection
import dev.fughetta.dshcompose.protocol.ProtocolClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the packaged runtime, authenticated client and deletion lifecycle together. */
@RunWith(AndroidJUnit4::class)
class SessionDeletionDeviceTest {
    @Test
    fun aNewLocalSessionCanBePermanentlyDeleted() =
        runBlocking<Unit>(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val runtime = AppRuntime.get(instrumentation.targetContext)
            if (runtime.phase == RuntimeController.Phase.NOT_INSTALLED) {
                val installed = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync {
                    runtime.install { error ->
                        if (error == null) installed.complete(Unit)
                        else
                            installed.completeExceptionally(
                                IllegalStateException("Runtime installation failed"),
                            )
                    }
                }
                withTimeout(180_000) { installed.await() }
            }
            val ready = CompletableDeferred<Unit>()
            val sink = RuntimeController.EventSink { event ->
                if (event["type"] == "status") {
                    when (runtime.phase) {
                        RuntimeController.Phase.RUNNING -> ready.complete(Unit)
                        RuntimeController.Phase.ERROR ->
                            ready.completeExceptionally(
                                IllegalStateException("Local runtime failed to start"),
                            )
                        else -> Unit
                    }
                }
            }
            runtime.addSink(sink)
            try {
                instrumentation.runOnMainSync {
                    if (runtime.phase == RuntimeController.Phase.RUNNING) ready.complete(Unit)
                    else runtime.start(emptyMap())
                }
                withTimeout(90_000) { ready.await() }
            } finally {
                runtime.removeSink(sink)
            }
            val status = runtime.status()
            val connection = HostConnection(requireNotNull(status["baseUrl"] as? String))
            ProtocolClient(connection).use { client ->
                connection.login(requireNotNull(status["token"] as? String))
                var created: String? = null
                var deletionAttempted = false
                try {
                    val id = client.createSession()
                    created = id
                    client.renameSession(id, "Session deletion device test")
                    assertTrue(client.listSessions().any { it.sessionId == id })
                    deletionAttempted = true
                    client.deleteSessionPermanently(id)
                    created = null
                    assertFalse(client.listSessions().any { it.sessionId == id })
                } finally {
                    // Only this test's newly created session is eligible for cleanup; never replay
                    // deletion.
                    if (!deletionAttempted) created?.let { client.deleteSessionPermanently(it) }
                }
            }
        }
}

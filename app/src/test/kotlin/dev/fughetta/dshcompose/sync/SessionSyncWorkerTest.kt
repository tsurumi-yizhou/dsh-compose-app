package dev.fughetta.dshcompose.sync

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.fughetta.dshcompose.ClientApplication
import dev.fughetta.dshcompose.data.link.LinkFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The worker must fail fast instead of retrying forever when the host cannot be opened: no endpoint
 * provider (cold start without UI), an endpoint that refuses to open, or a missing host id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ClientApplication::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionSyncWorkerTest {
    private val app: ClientApplication
        get() = RuntimeEnvironment.getApplication() as ClientApplication

    private fun worker(host: String?): SessionSyncWorker {
        val builder = TestListenableWorkerBuilder<SessionSyncWorker>(app)
        val data = host?.let { workDataOf(SessionSyncWorker.HOST to it) }
        return (if (data == null) builder else builder.setInputData(data)).build()
    }

    @Test
    fun aHostWithoutAnEndpointFailsInsteadOfRetrying() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)

        val result = runBlocking { worker("ssh-cold-start").doWork() }

        assertTrue(result is ListenableWorker.Result.Failure)
        assertFalse(app.dataSources.hostLinks.hasLink("ssh-cold-start"))
    }

    @Test
    fun anEndpointThatCannotOpenFailsTheRun() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.dataSources.hostLinks.provide { host ->
            if (host == "engine-stopped") suspend { throw LinkFailure("link/engine-stopped") }
            else null
        }

        val result = runBlocking { worker("engine-stopped").doWork() }

        assertTrue(result is ListenableWorker.Result.Failure)
        assertFalse(app.dataSources.hostLinks.hasLink("engine-stopped"))
    }

    @Test
    fun aRunWithoutAHostIdFailsImmediately() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)

        val result = runBlocking { worker(null).doWork() }

        assertTrue(result is ListenableWorker.Result.Failure)
    }
}

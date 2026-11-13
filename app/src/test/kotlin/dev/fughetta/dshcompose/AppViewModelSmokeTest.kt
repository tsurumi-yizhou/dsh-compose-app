package dev.fughetta.dshcompose

import dev.fughetta.dshcompose.domain.hosts.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Constructs the whole view-model graph once. It is the broadest wiring check available without a
 * device: a missing repository, a mistyped navigation state or a broken coordinator constructor
 * fails here instead of at the first screen that touches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ClientApplication::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppViewModelSmokeTest {
    private val app: ClientApplication
        get() = RuntimeEnvironment.getApplication() as ClientApplication

    @Test
    fun theViewModelGraphConstructsAndStartsOffline() = runBlocking {
        val viewModel = AppViewModel(app)

        assertNotNull(viewModel.state)
        assertNotNull(viewModel.state.value)
        assertEquals(LinkState.Offline, viewModel.state.value.linkState)
    }
}

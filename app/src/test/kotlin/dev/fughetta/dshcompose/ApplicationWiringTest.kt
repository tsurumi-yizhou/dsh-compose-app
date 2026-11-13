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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Application-lifetime endpoint registration is available before any screen is created. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = ClientApplication::class,
    manifest = Config.NONE,
    shadows = [RetiredDeviceKey::class],
    instrumentedPackages = ["dev.fughetta.dshcompose.data.hosts"],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ApplicationWiringTest {
    private val app: ClientApplication
        get() = RuntimeEnvironment.getApplication() as ClientApplication

    @Test
    fun repositoriesConstructWithoutAConnection() = runBlocking {
        assertNotNull(app.dataSources.hostLinks)
        assertNotNull(app.dataSources.sessionMirror)
        assertNotNull(app.sessionsRepository)
        assertNotNull(app.dataSources.hostRepository)
        assertNotNull(app.dataSources.remoteHostsRepository)
        assertNotNull(app.settingsRepository)

        val state = app.dataSources.hostLinks.state("unknown-host")
        assertEquals(LinkState.Offline, state.value)
        assertNull(app.dataSources.hostLinks.link("unknown-host"))
        assertFalse(app.dataSources.hostLinks.hasLink("unknown-host"))
    }

    @Test
    fun savedHostProvidersAreRegisteredWithoutAViewModel() = runBlocking {
        for ((host, code) in listOf("compose-missing" to "service/not-found")) {
            val error = runCatching { app.dataSources.hostLinks.ensure(host) }.exceptionOrNull()
            assertEquals(code, (error as dev.fughetta.dshcompose.data.link.LinkFailure).code)
        }
    }

    @Test
    fun theUnknownHostStateIsOfflineNotAnException() = runBlocking {
        // Resolving an unknown host must fail with a stable code rather than leaking a null client.
        val error = runCatching {
            app.dataSources.hostLinks.ensure("never-registered")
        }
            .exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is dev.fughetta.dshcompose.data.link.LinkFailure)
        assertEquals(
            "link/no-endpoint",
            (error as dev.fughetta.dshcompose.data.link.LinkFailure).code,
        )
    }
}

/** AndroidKeyStore is device-owned; this fixture exercises application wiring without a device. */
@Implements(
    className = "dev.fughetta.dshcompose.data.hosts.DeviceCredentialCipher",
    isInAndroidSdk = false,
)
class RetiredDeviceKey {
    @Implementation fun clearRetiredKey() {}
}

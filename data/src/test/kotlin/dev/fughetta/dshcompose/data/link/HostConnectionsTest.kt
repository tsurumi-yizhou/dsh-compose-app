package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.hosts.ConnectionStatus
import dev.fughetta.dshcompose.domain.hosts.LinkState
import dev.fughetta.dshcompose.protocol.HostConnection
import dev.fughetta.dshcompose.protocol.ProtocolClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostConnectionsTest {
    private fun client(): ProtocolClient =
        ProtocolClient(
            HostConnection(
                "http://localhost",
                engine =
                    MockEngine {
                        respond(
                            "{}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
            ),
        )

    private class EndpointStub(private val opens: suspend () -> ProtocolClient) {
        var count = 0

        suspend fun open(): ProtocolClient {
            count++
            return opens()
        }
    }

    @Test
    fun ensureOpensOneLinkAndReusesIt() = runTest {
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope)
        manager.register("local", endpoint::open)

        val first = manager.ensure("local")
        val second = manager.ensure("local")

        assertSame(first, second)
        assertEquals(1, endpoint.count)
        assertTrue(manager.state("local").value is LinkState.Online)
    }

    @Test
    fun concurrentOwnersShareOneLinkUntilBothRelease() = runTest {
        val gate = CompletableDeferred<Unit>()
        val endpoint = EndpointStub {
            gate.await()
            client()
        }
        val manager = HostConnections(backgroundScope, idleTimeoutMs = 1_000L)
        manager.register("blocked", endpoint::open)
        val background = launch { manager.acquire("blocked") }
        val foreground = launch { manager.acquire("blocked") }
        runCurrent()
        gate.complete(Unit)
        background.join()
        foreground.join()
        assertEquals(1, endpoint.count)
        manager.release("blocked")
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(manager.state("blocked").value is LinkState.Online)
        manager.release("blocked")
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(manager.state("blocked").value is LinkState.Offline)
    }

    @Test
    fun anOpeningResultCannotReplaceANewerOfferedClient() = runTest {
        val old = client()
        val fresh = client()
        val finish = CompletableDeferred<Unit>()
        val manager = HostConnections(backgroundScope)
        manager.register("ssh") {
            finish.await()
            old
        }
        val opening = async { manager.acquire("ssh") }
        runCurrent()
        manager.offer("ssh", fresh)
        finish.complete(Unit)
        assertSame(fresh, opening.await())
        assertSame(fresh, manager.link("ssh"))
        old.close()
        runCurrent()
        assertTrue(manager.state("ssh").value is LinkState.Online)
        manager.release("ssh")
        manager.detach("ssh")
        fresh.close()
    }

    @Test
    fun aFailedOpenIsReportedAndCanBeRetried() = runTest {
        var attempt = 0
        val endpoint = EndpointStub {
            attempt++
            if (attempt == 1) throw LinkFailure("ssh/connect-failed")
            client()
        }
        val manager = HostConnections(backgroundScope)
        manager.register("host", endpoint::open)

        val error = runCatching { manager.ensure("host") }.exceptionOrNull()
        assertEquals("ssh/connect-failed", (error as LinkFailure).code)
        assertEquals(LinkState.Failed("ssh/connect-failed"), manager.state("host").value)

        manager.ensure("host")
        assertTrue(manager.state("host").value is LinkState.Online)
        assertEquals(2, endpoint.count)
    }

    @Test
    fun anIdleLinkIsClosedAfterTheTimeout() = runTest {
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope, idleTimeoutMs = 1_000L)
        manager.register("local", endpoint::open)
        manager.ensure("local")

        manager.release("local")
        advanceTimeBy(999)
        runCurrent()
        assertTrue(manager.state("local").value is LinkState.Online)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(manager.state("local").value is LinkState.Offline)

        manager.ensure("local")
        assertEquals(2, endpoint.count)
    }

    @Test
    fun closeSwitchesTheHostOfflineImmediately() = runTest {
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope)
        manager.register("host", endpoint::open)
        manager.ensure("host")

        manager.close("host")

        assertTrue(manager.state("host").value is LinkState.Offline)
        manager.ensure("host")
        assertEquals(2, endpoint.count)
    }

    @Test
    fun ensureWithAnEndpointRegistersAndReusesTheLink() = runTest {
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope)

        val first = manager.ensure("local", endpoint::open)
        val second = manager.ensure("local", endpoint::open)

        assertSame(first, second)
        assertEquals(1, endpoint.count)
        assertTrue(manager.hasLink("local"))
    }

    @Test
    fun invalidatingDropsTheLinkSoTheNextEnsureReopensIt() = runTest {
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope)
        manager.ensure("local", endpoint::open)

        manager.invalidate("local")

        assertFalse(manager.hasLink("local"))
        assertTrue(manager.state("local").value is LinkState.Offline)
        manager.ensure("local", endpoint::open)
        assertEquals(2, endpoint.count)
    }

    @Test
    fun releaseAndCloseIgnoreHostsThatWereNeverRegistered() = runTest {
        val manager = HostConnections(backgroundScope)
        manager.release("missing")
        manager.close("missing")
        manager.invalidate("missing")
        assertTrue(manager.state("missing").value is LinkState.Offline)
    }

    @Test
    fun anOfferedLinkIsServedWithoutOpening() = runTest {
        val client = client()
        val manager = HostConnections(backgroundScope)

        manager.offer("ssh", client)

        assertSame(client, manager.ensure("ssh"))
        assertTrue(manager.hasLink("ssh"))
        assertTrue(manager.state("ssh").value is LinkState.Online)
    }

    @Test
    fun detachForgetsAnOfferedLinkWithoutClosingIt() = runTest {
        val client = client()
        val manager = HostConnections(backgroundScope)
        val fresh = client()
        manager.provide { id -> if (id == "ssh") suspend { fresh } else null }
        manager.offer("ssh", client)

        manager.detach("ssh")

        assertFalse(manager.hasLink("ssh"))
        assertTrue(manager.state("ssh").value is LinkState.Offline)
        assertNotEquals(ConnectionState.Closed, client.mux.state.value.state)
        assertSame(fresh, manager.ensure("ssh"))
    }

    @Test
    fun closingAnOfferedHostNeverClosesItsClient() = runTest {
        val client = client()
        val manager = HostConnections(backgroundScope)
        manager.offer("ssh", client)

        manager.close("ssh")

        assertTrue(manager.state("ssh").value is LinkState.Offline)
        assertNotEquals(ConnectionState.Closed, client.mux.state.value.state)
    }

    @Test
    fun linkReturnsTheAttachedClientOnly() = runTest {
        val client = client()
        val manager = HostConnections(backgroundScope)
        assertNull(manager.link("host"))

        manager.offer("host", client)
        assertSame(client, manager.link("host"))

        manager.detach("host")
        assertNull(manager.link("host"))
    }

    @Test
    fun linkStateFollowsTheMux() = runTest {
        val mux = MutableStateFlow(ConnectionStatus(ConnectionState.Idle))
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope, watch = { mux })
        manager.ensure("host", endpoint::open)
        runCurrent()
        assertTrue(manager.state("host").value is LinkState.Online)

        mux.value =
            ConnectionStatus(ConnectionState.Connected, ConnectionGeneration(1, "client", "/home"))
        runCurrent()
        val online = manager.state("host").value as LinkState.Online
        assertEquals(1L, online.generation?.number)

        mux.value = ConnectionStatus(ConnectionState.Reconnecting)
        runCurrent()
        assertTrue(manager.state("host").value is LinkState.Connecting)

        mux.value = ConnectionStatus(ConnectionState.Unauthorized)
        runCurrent()
        assertEquals(LinkState.Failed("link/unauthorized"), manager.state("host").value)

        mux.value = ConnectionStatus(ConnectionState.Closed)
        runCurrent()
        assertTrue(manager.state("host").value is LinkState.Offline)
        manager.close("host")
    }

    @Test
    fun ensureResolvesTheEndpointFromTheRegisteredProvider() = runTest {
        val endpoint = EndpointStub { client() }
        val manager = HostConnections(backgroundScope)
        manager.provide { hostId -> if (hostId == "local") endpoint::open else null }

        val first = manager.ensure("local")
        val second = manager.ensure("local")

        assertSame(first, second)
        assertEquals(1, endpoint.count)
        assertTrue(manager.state("local").value is LinkState.Online)
    }

    @Test
    fun providersAreTriedInOrder() = runTest {
        val first = EndpointStub { client() }
        val second = EndpointStub { client() }
        val manager = HostConnections(backgroundScope)
        manager.provide { null }
        manager.provide { if (it == "second") second::open else null }
        manager.provide { if (it == "first") first::open else null }

        manager.ensure("first")

        assertEquals(1, first.count)
        assertEquals(0, second.count)
    }

    @Test
    fun aHostWithoutAProviderEndpointReportsTheLinkFailure() = runTest {
        val manager = HostConnections(backgroundScope)
        manager.provide { null }

        val error = runCatching { manager.ensure("host") }.exceptionOrNull()
        assertEquals("link/no-endpoint", (error as LinkFailure).code)
    }

    @Test
    fun anUnregisteredHostWithoutAProviderIsRejected() = runTest {
        val manager = HostConnections(backgroundScope)
        val error = runCatching { manager.ensure("nope") }.exceptionOrNull()
        assertEquals("link/no-endpoint", (error as LinkFailure).code)
    }
}

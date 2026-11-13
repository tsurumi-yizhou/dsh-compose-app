package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.presets.EngineAgentPresetsRepository
import dev.fughetta.dshcompose.data.providers.EngineProvidersRepository
import dev.fughetta.dshcompose.data.providers.EngineSessionModelsRepository
import dev.fughetta.dshcompose.data.providers.EngineSubscriptionsRepository
import dev.fughetta.dshcompose.data.sessions.EngineAgentTeamsRepository
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.protocol.HostConnection
import dev.fughetta.dshcompose.protocol.ProtocolClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionSessionTest {
    @Test
    fun switchingHostsCancelsOldSubscriptionsWithoutClosingSharedClient() = runBlocking {
        val oldClient = ProtocolClient(HostConnection("http://localhost:3080"))
        val sharedClient = ProtocolClient(HostConnection("http://localhost:3081"))
        val attachments = dev.fughetta.dshcompose.data.messages.AttachmentConnections()
        val reasoning = dev.fughetta.dshcompose.data.messages.EngineMessagesRepository(this)
        val models = EngineSessionModelsRepository()
        val providers = EngineProvidersRepository()
        val subscriptions = EngineSubscriptionsRepository()
        val presets = EngineAgentPresetsRepository()
        val teams = EngineAgentTeamsRepository()
        val old =
            ConnectionSession(
                oldClient,
                this,
                ownsClient = true,
                attachments,
                reasoning,
                models,
                providers,
                subscriptions,
                presets,
                teams,
                "old",
            )
        val next =
            ConnectionSession(
                sharedClient,
                this,
                ownsClient = false,
                attachments,
                reasoning,
                models,
                providers,
                subscriptions,
                presets,
                teams,
                "next",
            )
        try {
            val oldSubscription =
                old.scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val nextSubscription =
                next.scope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            old.close()
            assertSame(next.messageAttachments, attachments.connection.value)
            oldSubscription.join()
            assertTrue(oldSubscription.isCancelled)
            assertTrue(nextSubscription.isActive)
            assertEquals(ConnectionState.Closed, oldClient.mux.state.value.state)
            next.close()
            assertNull(attachments.connection.value)
            nextSubscription.join()
            assertTrue(nextSubscription.isCancelled)
            assertEquals(ConnectionState.Idle, sharedClient.mux.state.value.state)
            assertTrue(isActive)
        } finally {
            old.close()
            next.close()
            sharedClient.close()
        }
    }
}

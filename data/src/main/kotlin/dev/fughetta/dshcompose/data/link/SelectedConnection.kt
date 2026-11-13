package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.goals.EngineGoalsRepository
import dev.fughetta.dshcompose.data.jobs.EngineJobsRepository
import dev.fughetta.dshcompose.data.media.EngineMediaRepository
import dev.fughetta.dshcompose.data.messages.AttachmentConnections
import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.presets.EngineAgentPresetsRepository
import dev.fughetta.dshcompose.data.providers.EngineProvidersRepository
import dev.fughetta.dshcompose.data.providers.EngineSessionModelsRepository
import dev.fughetta.dshcompose.data.providers.EngineSubscriptionsRepository
import dev.fughetta.dshcompose.data.schedules.EngineSchedulesRepository
import dev.fughetta.dshcompose.data.sessions.EngineAgentTeamsRepository
import dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository
import dev.fughetta.dshcompose.data.settings.AppSettingsRepository
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.hosts.ConnectionStatus
import dev.fughetta.dshcompose.domain.hosts.HostSelection
import dev.fughetta.dshcompose.protocol.ProtocolClient
import dev.fughetta.dshcompose.protocol.string
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Owns repository bindings for the selected host. Shared transports belong to HostConnections. */
class SelectedConnection(
    internal val parent: CoroutineScope,
    private val attachments: AttachmentConnections,
    private val reasoning: EngineMessagesRepository,
    private val models: EngineSessionModelsRepository,
    private val providers: EngineProvidersRepository,
    private val subscriptions: EngineSubscriptionsRepository,
    private val presets: EngineAgentPresetsRepository,
    private val teams: EngineAgentTeamsRepository,
    private val sessions: EngineSessionsRepository,
    private val settings: AppSettingsRepository,
    private val schedules: EngineSchedulesRepository,
    private val goals: EngineGoalsRepository,
    private val jobs: EngineJobsRepository,
    private val media: EngineMediaRepository,
) : AutoCloseable {
    private val publishedSession = MutableStateFlow<ConnectedHost?>(null)
    var session: ConnectionSession? = null
        private set(value) {
            field = value
            publishedSession.value = value?.view
        }

    private val mutableState = MutableStateFlow(ConnectionStatus(ConnectionState.Idle))
    val state = mutableState.asStateFlow()
    val view = HostSelection(state, publishedSession.asStateFlow())
    private var selectedSessionId: String? = null
    private var retainedLink: Pair<HostConnections, String>? = null

    suspend fun open(
        links: HostConnections,
        host: String,
        isCurrent: () -> Boolean,
    ): ConnectionSession? {
        var acquired = false
        try {
            val client = links.acquire(host)
            acquired = true
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return null
            return connect(client, host).also {
                retainedLink = links to host
                acquired = false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isCurrent()) sessions.connectionAttemptFinished()
            throw error
        } finally {
            if (acquired) links.release(host)
        }
    }

    internal fun connect(client: ProtocolClient, host: String): ConnectionSession {
        disconnect(loading = true)
        val next =
            ConnectionSession(
                client,
                parent,
                false,
                attachments,
                reasoning,
                models,
                providers,
                subscriptions,
                presets,
                teams,
                host,
            )
        session = next
        next.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var catalogGeneration: dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration? = null
            client.mux.state.collect { link ->
                if (session !== next) return@collect
                val available = client.takeIf { link.state == ConnectionState.Connected }
                if (available != null && catalogGeneration != link.generation) {
                    sessions.connect(available, next.scope, host)
                    catalogGeneration = link.generation
                } else if (available == null && catalogGeneration != null) {
                    sessions.disconnect(loading = true)
                    catalogGeneration = null
                }
                settings.connect(available)
                schedules.connect(available, next.scope)
                goals.connect(available, next.scope)
                goals.session(selectedSessionId)
                jobs.connect(available, next.scope)
                media.connect(available)
                next.bindRepositories(sessions.connection.value, settings.connection.value)
                // Consumers must see the new repository handles before the ready state.
                mutableState.value = link
            }
        }
        next.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.mux.hostEvents.collect { event ->
                if (session !== next) return@collect
                if (event.string("type") != "emit") return@collect
                sessions.onHostEvent(client, event)
                val name = event.string("event")
                val namespace =
                    ((event["args"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
                if (
                    name in
                        setOf(
                            "credentials/reference-updated",
                            "credentials/record-updated",
                            "llm/adapters-updated",
                        ) || name == "settings/document-updated" && namespace == "llm-pi-ai"
                ) {
                    providers.invalidate()
                    subscriptions.invalidate()
                }
                if (
                    name == "agent-preset/selected" ||
                        name == "settings/document-updated" && namespace == "agent-preset-registry"
                )
                    presets.invalidate()
            }
        }
        return next
    }

    fun selectSession(id: String?) {
        selectedSessionId = id
        goals.session(id)
    }

    fun disconnect(loading: Boolean = false) {
        val previous = session
        session = null
        previous?.close()
        retainedLink?.let { (links, host) -> links.release(host) }
        retainedLink = null
        media.disconnect()
        jobs.close()
        goals.close()
        schedules.close()
        settings.disconnect()
        sessions.disconnect(loading)
        mutableState.value = ConnectionStatus(ConnectionState.Idle)
    }

    override fun close() = disconnect()
}

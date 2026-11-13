package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.messages.AttachmentConnections
import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.messages.hostRequests
import dev.fughetta.dshcompose.data.presets.EngineAgentPresetsRepository
import dev.fughetta.dshcompose.data.providers.EngineProvidersRepository
import dev.fughetta.dshcompose.data.providers.EngineSessionModelsRepository
import dev.fughetta.dshcompose.data.providers.EngineSubscriptionsRepository
import dev.fughetta.dshcompose.data.sessions.EngineAgentTeamsRepository
import dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository
import dev.fughetta.dshcompose.data.sessions.EngineTranscriptRepository
import dev.fughetta.dshcompose.data.sessions.TranscriptClock
import dev.fughetta.dshcompose.data.sync.TranscriptMirrorSink
import dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.commands.CommandResult
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.hosts.HostRepositories
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.domain.sessions.SubagentAddress
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import dev.fughetta.dshcompose.protocol.ConnectionClosedException
import dev.fughetta.dshcompose.protocol.ProtocolClient
import dev.fughetta.dshcompose.protocol.subagentRoute
import dev.fughetta.dshcompose.protocol.toToolPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*

/** Owns one selected host's subscriptions and, for non-shared clients, its transport. */
class ConnectionSession
internal constructor(
    internal val client: ProtocolClient,
    parent: CoroutineScope,
    private val ownsClient: Boolean,
    private val attachments: AttachmentConnections,
    reasoning: EngineMessagesRepository,
    models: EngineSessionModelsRepository,
    providers: EngineProvidersRepository,
    subscriptions: EngineSubscriptionsRepository,
    presets: EngineAgentPresetsRepository,
    teams: EngineAgentTeamsRepository,
    val hostId: String,
) : AutoCloseable {
    val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val state = client.mux.state
    val requests =
        combine(state, client.mux.pendingRequests) { status, requests ->
                hostRequests(status.generation, requests)
            }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                hostRequests(state.value.generation, client.mux.pendingRequests.value),
            )
    val hostEvents =
        client.mux.hostEvents
            .map { it.toToolPayload() as ToolPayload.Obj }
            .shareIn(scope, SharingStarted.Eagerly)

    val messageAttachments: AttachmentsRepository = attachments.connect(client, scope)
    private val reasoningConnection = reasoning.connectTranslations(client, scope)
    private val modelsConnection = models.connect(client, scope)
    private val providersConnection = providers.connect(client, scope)
    private val subscriptionsConnection = subscriptions.connect(client, scope)
    private val presetsConnection = presets.connect(client, scope)
    private val teamsConnection = teams.connect(client, scope, hostId)
    private val publishedBindings = MutableStateFlow<HostRepositories?>(null)
    val view =
        ConnectedHost(
            hostId,
            scope,
            state,
            requests,
            hostEvents,
            messageAttachments,
            publishedBindings.asStateFlow(),
        )
    private var closed = false
    @Volatile
    var repositories: ConnectionRepositories? = null
        private set(value) {
            field = value
            publishedBindings.value = value?.view
        }

    internal fun bindRepositories(
        sessions: SessionsRepository?,
        settings: SettingsRepository?,
    ) {
        val generation = client.mux.state.value.generation
        if (sessions != null && settings != null && repositories?.generation == generation) return
        repositories?.close()
        repositories =
            if (sessions != null && settings != null)
                ConnectionRepositories(client, sessions, settings)
            else null
    }

    fun isLinkedTo(links: HostConnections, host: String): Boolean =
        hostId == host && links.link(host) === client

    suspend fun prompt(id: String, text: String, timeZone: String?) {
        val generation = state.value.generation
        client.withGeneration(generation, { !closed }) {
            client.prompt(id, text, timeZone = timeZone)
        }
    }

    fun subagentRoute(id: String): SubagentAddress? = client.subagentRoute(id)

    fun conversation(
        id: String,
        pending: EngineMessagesRepository,
        catalog: EngineSessionsRepository,
        clock: TranscriptClock,
        initialCommand: Pair<CommandResult, String>?,
        mirror: TranscriptMirrorSink,
    ): EngineTranscriptRepository {
        if (closed) throw ConnectionClosedException()
        return EngineTranscriptRepository(
            client,
            hostId,
            id,
            scope,
            pending,
            catalog,
            clock,
            initialCommand,
            mirror,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        repositories?.close()
        repositories = null
        teamsConnection.close()
        presetsConnection.close()
        subscriptionsConnection.close()
        providersConnection.close()
        modelsConnection.close()
        reasoningConnection.close()
        attachments.disconnect(messageAttachments)
        scope.cancel()
        if (ownsClient) client.close()
    }
}

package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.plugins.EnginePluginsRepository
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.plugins.PluginApplication
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class EngineAgentTeamsRepository : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<AgentTeamsRepository?>(null)
    val connection = mutable.asStateFlow()
    private var owner: Connection? = null

    fun connect(client: ProtocolClient, parent: CoroutineScope, hostId: String): AutoCloseable {
        val next = Connection(client, parent, hostId)
        val previous =
            synchronized(lock) {
                owner.also {
                    owner = next
                    mutable.value = null
                }
            }
        previous?.close()
        next.scope.coroutineContext.job.invokeOnCompletion { next.close() }
        next.publish(
            client.mux.state.value.takeIf { it.state == ConnectionState.Connected }?.generation,
        )
        next.scope.launch {
            client.mux.state
                .map { it.takeIf { state -> state.state == ConnectionState.Connected }?.generation }
                .distinctUntilChanged()
                .collect(next::publish)
        }
        return next
    }

    private inner class Connection(
        val client: ProtocolClient,
        parent: CoroutineScope,
        val hostId: String,
    ) : AutoCloseable {
        val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        var current: ConnectedRepository? = null

        fun publish(generation: ConnectionGeneration?) {
            val previous =
                synchronized(lock) {
                    if (
                        owner !== this ||
                            !scope.isActive ||
                            client.mux.state.value
                                .takeIf { it.state == ConnectionState.Connected }
                                ?.generation != generation ||
                            current?.lease == generation
                    )
                        return
                    current.also {
                        val next = generation?.let {
                            ConnectedRepository(client, it, scope, hostId)
                        }
                        current = next
                        mutable.value = next
                    }
                }
            previous?.close()
        }

        override fun close() {
            synchronized(lock) {
                if (owner === this) {
                    owner = null
                    mutable.value = null
                }
            }
            current?.close()
            scope.cancel()
        }
    }

    fun invalidateProfile() {
        synchronized(lock) { owner?.current }?.invalidateProfile()
    }

    private class ConnectedRepository(
        private val client: ProtocolClient,
        val lease: ConnectionGeneration,
        parent: CoroutineScope,
        override val hostId: String,
    ) : AgentTeamsRepository, AutoCloseable {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val updates = MutableSharedFlow<AgentTeamChange>(extraBufferCapacity = 8)
        override val changes = updates.asSharedFlow()
        private val cacheLock = Any()
        private var profileRead: Deferred<AgentTeamProfile>? = null

        private val hostChanges =
            client.mux.hostEvents.mapNotNull { event ->
                if ((event["type"] as? JsonPrimitive)?.contentOrNull != "emit")
                    return@mapNotNull null
                when ((event["event"] as? JsonPrimitive)?.contentOrNull) {
                    "api-session/status" ->
                        ((event["args"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)
                            ?.contentOrNull
                            ?.let { AgentTeamChange.SessionStatus(it) }
                    "plugin-manager/changed" -> AgentTeamChange.ProfileChanged
                    else -> null
                }
            }

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                hostChanges.collect { change ->
                    ensureCurrent()
                    if (change == AgentTeamChange.ProfileChanged) invalidateProfile()
                    updates.emit(change)
                }
            }
        }

        private fun ensureCurrent() {
            if (
                !scope.isActive ||
                    client.mux.state.value
                        .takeIf { it.state == ConnectionState.Connected }
                        ?.generation != lease
            )
                throw CancellationException("Agent team connection retired")
        }

        private suspend fun <T> request(action: suspend () -> T): T {
            ensureCurrent()
            val task =
                scope.async(Dispatchers.IO) {
                    ensureCurrent()
                    action()
                }
            try {
                return task.await().also { ensureCurrent() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentTeamOperationException) {
                throw error
            } catch (error: Exception) {
                throw AgentTeamOperationException(
                    (error as? HostRemoteException)?.code ?: "agents/request-failed",
                    error,
                )
            } finally {
                task.cancel()
            }
        }

        override suspend fun projection(sessionId: String) = request {
            val projection = client.projections(sessionId)
            AgentTeamProjection(
                parseAgentTeamSnapshot(
                    (projection?.get("values") as? JsonObject)?.get("agentTeam"),
                ),
                projection?.let(::decodeSessionSubagents),
            )
        }

        override suspend fun profile(): AgentTeamProfile {
            ensureCurrent()
            val pending =
                synchronized(cacheLock) {
                    profileRead
                        ?: scope
                            .async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                                request {
                                    val bundle =
                                        EnginePluginsRepository(client).bundles().firstOrNull {
                                            it.name == TEAM_BUNDLE
                                        }
                                    AgentTeamProfile(bundle != null, bundle?.enabled == true)
                                }
                            }
                            .also { profileRead = it }
                }
            try {
                return pending.await().also { ensureCurrent() }
            } catch (error: Exception) {
                if (pending.isCompleted)
                    synchronized(cacheLock) { if (profileRead === pending) profileRead = null }
                throw error
            }
        }

        override fun invalidateProfile() {
            synchronized(cacheLock) { profileRead = null }
        }

        override suspend fun teamMessages(leadId: String, members: List<AgentTeamMember>) =
            readAgentTeamMessages(this, leadId, members)

        override suspend fun messages(leadId: String, member: AgentTeamMember) = request {
            if (!member.lead)
                client.registerSubagentRoute(
                    SubagentAddress(leadId, member.id, SubagentMode.Continuable),
                )
            client.agentTeamMessages(member.id)
        }

        override suspend fun prepareOpen(address: SubagentAddress) = request {
            client.resolveSubagentMode(address).also(client::registerSubagentRoute)
        }

        override suspend fun interrupt(address: SubagentAddress) = request {
            client.interruptSubagent(address)
        }

        override suspend fun enableProfile(): Boolean = request {
            val result = EnginePluginsRepository(client).setBundleEnabled(TEAM_BUNDLE, true)
            if (!result.succeeded)
                throw AgentTeamOperationException(result.error?.code ?: "team/enable-failed")
            result.application == PluginApplication.RestartRequired
        }
            .also { invalidateProfile() }

        private companion object {
            const val TEAM_BUNDLE = "@deepseek-ai/dsh-experimental-agent-team-profile"
        }

        override fun close() {
            scope.cancel()
        }
    }

    override fun close() {
        val previous =
            synchronized(lock) {
                owner.also {
                    owner = null
                    mutable.value = null
                }
            }
        previous?.close()
    }
}

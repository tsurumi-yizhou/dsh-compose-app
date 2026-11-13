package dev.fughetta.dshcompose.data.presets

import dev.fughetta.dshcompose.data.plugins.EnginePluginsRepository
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.plugins.PluginApplication
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class EngineAgentPresetsRepository : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<PresetsRepository?>(null)
    val connection = mutable.asStateFlow()
    private var owner: Connection? = null

    fun connect(client: ProtocolClient, parent: CoroutineScope): AutoCloseable {
        val next = Connection(client, parent)
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
                        val next = generation?.let { lease ->
                            ConnectedRepository(client, lease, scope)
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

    fun invalidate() {
        synchronized(lock) { owner?.current }?.invalidate()
    }

    private class ConnectedRepository(
        private val client: ProtocolClient,
        val lease: ConnectionGeneration,
        parent: CoroutineScope,
    ) : PresetsRepository, AutoCloseable {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        private fun ensureCurrent() {
            if (
                !scope.isActive ||
                    client.mux.state.value
                        .takeIf { it.state == ConnectionState.Connected }
                        ?.generation != lease
            )
                throw CancellationException("Preset connection retired")
        }

        private suspend fun <T> request(
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
            action: suspend () -> T,
        ): T {
            ensureCurrent()
            val task =
                scope.async(dispatcher) {
                    ensureCurrent()
                    action()
                }
            try {
                return task.await().also { ensureCurrent() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is PresetOperationException) throw error
                throw PresetOperationException(
                    (error as? HostRemoteException)?.code ?: "preset/request-failed",
                    error,
                )
            } finally {
                task.cancel()
            }
        }

        private val mutableChanges = MutableStateFlow(0L)
        override val changes = mutableChanges.asStateFlow()
        override val supportsRemoval
            get() = client.connection.supportsBundleRemoval

        private suspend fun readCatalog(): AgentPresetCatalog = supervisorScope {
            val presets = async { client.agentPresets() }
            val bundles = async {
                try {
                    EnginePluginsRepository(client).bundles().map { bundle ->
                        PresetInstallation(
                            bundle.name,
                            bundle.removable,
                            bundle.official,
                            bundle.optional,
                            bundle.rows
                                .filter {
                                    it.moduleName == AGENT_PRESET_MODULE &&
                                        it.rowId.startsWith("preset-")
                                }
                                .map { it.rowId.removePrefix("preset-") }
                                .toSet(),
                        )
                    } to false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    emptyList<PresetInstallation>() to true
                }
            }
            val rows = presets.await()
            bundles.await().let { AgentPresetCatalog(rows, it.first, it.second) }
        }

        override suspend fun catalog(): AgentPresetCatalog = request { readCatalog() }

        override suspend fun read(id: String): AgentPresetDocument = request {
            client.readAgentPreset(id).also {
                if (it.id != id) throw HostProtocolException("Preset document id mismatch")
            }
        }

        override suspend fun composition(id: String): PresetComposition {
            val document = read(id)
            return request(Dispatchers.Default) { parsePresetComposition(document.content) }
        }

        override suspend fun defaults(): AgentPresetDefaults? = request {
            client.agentPresetDefaults()
        }

        override suspend fun setDefault(id: String, previous: AgentPresetDefaults) {
            request { client.setDefaultAgentPreset(id, previous) }
            invalidate()
        }

        override suspend fun select(sessionId: String, preset: String): String = request {
            client.selectAgentPreset(sessionId, preset)
        }

        override suspend fun render(
            composition: PresetComposition,
            enabled: Set<String>,
            prompt: String,
            complete: Boolean,
        ): String = request(Dispatchers.Default) { composition.render(enabled, prompt, complete) }

        override suspend fun packageFiles(bundle: PresetBundle): PresetPackageFiles =
            request(Dispatchers.Default) {
                PresetPackageFiles(bundle, bundle.directory, bundle.files())
            }

        override suspend fun createSession(): String = request { client.createSession() }

        override suspend fun prompt(sessionId: String, text: String, timeZone: String) = request {
            client.prompt(sessionId, text, timeZone = timeZone)
            Unit
        }

        override suspend fun remove(name: String): PresetRemovalResult {
            val result = request {
                val result = EnginePluginsRepository(client).removeBundle(name)
                if (!result.succeeded) throw PresetOperationException("preset/remove-failed")
                PresetRemovalResult(result.application == PluginApplication.RestartRequired)
            }
            invalidate()
            return result
        }

        override fun invalidate() {
            mutableChanges.update { it + 1 }
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

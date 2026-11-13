package dev.fughetta.dshcompose.data.providers

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class EngineProvidersRepository(private val onChanged: () -> Unit = {}) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<ProvidersRepository?>(null)
    val connection = mutable.asStateFlow()
    private var owner: Connection? = null

    fun connect(source: ProtocolClient, parent: CoroutineScope): AutoCloseable {
        val next = Connection(source, parent)
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
            source.mux.state.value.takeIf { it.state == ConnectionState.Connected }?.generation,
        )
        next.scope.launch {
            source.mux.state
                .map {
                    it.takeIf { status -> status.state == ConnectionState.Connected }?.generation
                }
                .distinctUntilChanged()
                .collect(next::publish)
        }
        return next
    }

    private inner class Connection(
        val source: ProtocolClient,
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
                            source.mux.state.value
                                .takeIf { it.state == ConnectionState.Connected }
                                ?.generation != generation ||
                            current?.lease == generation
                    )
                        return
                    current.also {
                        val next = generation?.let { lease ->
                            ConnectedRepository(source, lease, scope, onChanged)
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
        private val source: ProtocolClient,
        val lease: ConnectionGeneration,
        parent: CoroutineScope,
        private val onChanged: () -> Unit,
    ) : ProvidersRepository, AutoCloseable {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        private fun ensureCurrent() {
            if (
                !scope.isActive ||
                    source.mux.state.value
                        .takeIf { it.state == ConnectionState.Connected }
                        ?.generation != lease
            )
                throw CancellationException("Provider connection retired")
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
            } catch (error: Exception) {
                throw ProviderOperationException(
                    (error as? HostRemoteException)?.code ?: "providers/request-failed",
                    error,
                )
            } finally {
                task.cancel()
            }
        }

        private val mutableChanges = MutableStateFlow(0L)
        override val changes = mutableChanges.asStateFlow()
        private val mutableSnapshot = MutableStateFlow<ProviderSnapshot?>(null)
        override val snapshot = mutableSnapshot.asStateFlow()

        private val snapshotLock = Any()
        private var readNumber = 0L

        private suspend fun readSnapshot(): ProviderSnapshot {
            val settings = source.providerSettings()
            val accounts = settings.accounts()
            return ProviderSnapshot(
                settings,
                accounts,
                source.describeCredentials(accounts.mapNotNull { it.credentialRef }),
            )
        }

        private suspend fun applyMutation(change: ProviderMutation) =
            when (change) {
                is ProviderMutation.Create ->
                    source.createProvider(
                        change.route,
                        change.name,
                        change.api,
                        change.baseUrl,
                        change.credentialRef,
                        change.models,
                        change.revision,
                    )
                is ProviderMutation.Update ->
                    source.updateProvider(
                        change.previous,
                        change.name,
                        change.credentialRef,
                        change.api,
                        change.baseUrl,
                        change.models,
                        change.revision,
                        change.overrides,
                    )
                is ProviderMutation.Remove -> source.removeProvider(change.route, change.revision)
            }

        override suspend fun refresh(): ProviderSnapshot {
            val number = synchronized(snapshotLock) { ++readNumber }
            val result = request { readSnapshot() }
            synchronized(snapshotLock) { if (number == readNumber) mutableSnapshot.value = result }
            return result
        }

        override suspend fun setCredential(ref: String, key: ProviderKey?) {
            request {
                if (key == null) source.clearProviderKey(ref) else source.setProviderKey(ref, key)
            }
            invalidate()
            onChanged()
        }

        override suspend fun apply(change: ProviderMutation): ProviderSettings {
            val result = request { applyMutation(change) }
            invalidate()
            onChanged()
            return result
        }

        override suspend fun catalog(): ModelCatalog = request { source.listModels() }

        override suspend fun discover(route: String): PersistentList<DiscoveredModel> = request {
            source.discoverModels(route)
        }

        override suspend fun discover(
            baseUrl: String,
            api: ProviderApi,
            key: ProviderKey?,
        ): PersistentList<DiscoveredModel> = request { source.discoverModels(baseUrl, api, key) }

        override fun invalidate() {
            synchronized(snapshotLock) { readNumber++ }
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

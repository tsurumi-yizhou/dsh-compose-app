package dev.fughetta.dshcompose.data.providers

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private fun modelOperationFailure(error: Exception): String =
    when (error) {
        is ModelOperationException -> error.code
        is HostRemoteException -> error.code
        is HostHttpException,
        is HostTransportException,
        is ConnectionClosedException -> "connection-lost"
        is HostProtocolException -> "protocol-error"
        else -> "failed"
    }

class EngineSessionModelsRepository : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<SessionModelsRepository?>(null)
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
                .map { it.takeIf { state -> state.state == ConnectionState.Connected }?.generation }
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
        private var operations: ConnectedRepository? = null

        fun publish(generation: ConnectionGeneration?) {
            val previous =
                synchronized(lock) {
                    if (
                        owner !== this ||
                            !scope.isActive ||
                            source.mux.state.value
                                .takeIf { it.state == ConnectionState.Connected }
                                ?.generation != generation ||
                            operations?.lease == generation
                    )
                        return
                    operations.also {
                        val next = generation?.let { lease ->
                            ConnectedRepository(source, lease, scope)
                        }
                        operations = next
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
            scope.cancel()
        }
    }

    private class ConnectedRepository(
        private val source: ProtocolClient,
        val lease: ConnectionGeneration,
        parent: CoroutineScope,
    ) : SessionModelsRepository, AutoCloseable {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        private fun ensureCurrent() {
            if (
                !scope.isActive ||
                    source.mux.state.value
                        .takeIf { it.state == ConnectionState.Connected }
                        ?.generation != lease
            )
                throw CancellationException("Model catalog connection retired")
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
                throw ModelOperationException(modelOperationFailure(error), error)
            } finally {
                task.cancel()
            }
        }

        private val mutableChanges = MutableStateFlow(0L)
        override val changes = mutableChanges.asStateFlow()

        override suspend fun defaults(): ModelDefaults? = request { source.modelDefaults() }

        override suspend fun setDefaults(
            previous: ModelDefaults,
            selection: ModelSelection,
            remember: Boolean,
        ) {
            request { source.setModelDefaults(previous, selection, remember) }
            invalidate()
        }

        private val cacheLock = Any()
        private var cached: Deferred<ModelCatalog>? = null

        override suspend fun catalog(): ModelCatalog {
            ensureCurrent()
            val read =
                synchronized(cacheLock) {
                    cached
                        ?: scope
                            .async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                                ensureCurrent()
                                source.listModels()
                            }
                            .also { cached = it }
                }
            read.start()
            try {
                return read.await().also { ensureCurrent() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                synchronized(cacheLock) { if (cached === read) cached = null }
                throw ModelOperationException(modelOperationFailure(error), error)
            }
        }

        override suspend fun select(
            sessionId: String,
            selection: ModelSelection,
        ): ModelSelectionResult {
            val result = request { source.selectModel(sessionId, selection) }
            invalidate()
            return result
        }

        override fun invalidate() {
            val previous = synchronized(cacheLock) { cached.also { cached = null } }
            previous?.cancel()
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

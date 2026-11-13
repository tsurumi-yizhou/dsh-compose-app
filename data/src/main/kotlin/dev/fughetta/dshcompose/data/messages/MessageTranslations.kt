package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class MessageTranslations : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<ReasoningTranslation?>(null)
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
        private var operations: ConnectedRepository? = null

        fun publish(generation: ConnectionGeneration?) {
            val previous =
                synchronized(lock) {
                    if (
                        owner !== this ||
                            !scope.isActive ||
                            client.mux.state.value
                                .takeIf { it.state == ConnectionState.Connected }
                                ?.generation != generation ||
                            operations?.lease == generation
                    )
                        return
                    operations.also {
                        val next = generation?.let { lease ->
                            ConnectedRepository(client, lease, scope)
                        }
                        operations = next
                        mutable.value = next?.view
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
        private val client: ProtocolClient,
        val lease: ConnectionGeneration,
        parent: CoroutineScope,
    ) : AutoCloseable {
        val view = ReasoningTranslation(lease, ::limits, ::translate)
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

        private fun ensureCurrent() {
            if (
                !scope.isActive ||
                    client.mux.state.value
                        .takeIf { it.state == ConnectionState.Connected }
                        ?.generation != lease
            )
                throw CancellationException("Translation connection retired")
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
                val code =
                    when (error) {
                        is HostRemoteException -> error.code
                        is HostProtocolException -> "protocol-error"
                        is ConnectionClosedException,
                        is HostTransportException,
                        is HostHttpException -> "connection-lost"
                        else -> "failed"
                    }
                throw ReasoningTranslationException(code, error)
            } finally {
                task.cancel()
            }
        }

        suspend fun limits() = request { client.cotTranslationLimits() }

        suspend fun translate(
            sessionId: String,
            text: String,
            limits: ReasoningTranslationLimits,
            targetLanguage: String,
        ) = request { client.translateReasoning(text, targetLanguage, limits.provider, sessionId) }

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

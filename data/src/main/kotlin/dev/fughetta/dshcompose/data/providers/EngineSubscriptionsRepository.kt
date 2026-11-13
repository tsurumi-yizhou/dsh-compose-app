package dev.fughetta.dshcompose.data.providers

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EngineSubscriptionsRepository(private val onChanged: () -> Unit = {}) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<SubscriptionsRepository?>(null)
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
    ) : SubscriptionsRepository, AutoCloseable {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val attemptsLock = Any()
        private val attempts = mutableSetOf<Login>()
        private val mutableChanges = MutableStateFlow(0L)
        override val changes = mutableChanges.asStateFlow()

        private fun ensureCurrent() {
            if (
                !scope.isActive ||
                    source.mux.state.value
                        .takeIf { it.state == ConnectionState.Connected }
                        ?.generation != lease
            )
                throw CancellationException("Subscription connection retired")
        }

        private fun failure(error: Exception) =
            SubscriptionOperationException(
                (error as? HostRemoteException)?.code ?: "subscriptions/request-failed",
                error,
            )

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
                throw failure(error)
            } finally {
                task.cancel()
            }
        }

        override suspend fun statuses(): PersistentList<SubscriptionStatus> = request {
            source.subscriptions()
        }

        override suspend fun begin(subscription: Subscription): SubscriptionLogin {
            ensureCurrent()
            var created: Login? = null
            try {
                return withContext(Dispatchers.IO) {
                        withContext(NonCancellable) {
                            ensureCurrent()
                            val login = Login(source.beginLogin(subscription))
                            created = login
                            synchronized(attemptsLock) { attempts.add(login) }
                            ensureCurrent()
                            SubscriptionLogin(
                                poll = { login.poll() },
                                answer = { login.answer(it) },
                                cancel = { login.cancel() },
                                release = { login.close() },
                            )
                        }
                    }
                    .also { ensureCurrent() }
            } catch (error: CancellationException) {
                created?.close()
                throw error
            } catch (error: Exception) {
                created?.close()
                throw failure(error)
            }
        }

        override suspend fun signOut(subscription: Subscription) {
            request { source.signOut(subscription) }
            onChanged()
        }

        override fun invalidate() {
            mutableChanges.update { it + 1 }
        }

        private inner class Login(private var id: String?) {
            private val gate = Mutex()

            private fun identifier() = synchronized(attemptsLock) { id }

            private fun finish(expected: String): Boolean =
                synchronized(attemptsLock) {
                    if (id != expected) false
                    else {
                        id = null
                        attempts.remove(this)
                        true
                    }
                }

            suspend fun poll(): LoginStep = gate.withLock {
                val current =
                    identifier()
                        ?: throw SubscriptionOperationException("subscriptions/attempt-finished")
                val step = request { source.pollLogin(current) }
                if (step.phase.finished && finish(current) && step.phase == LoginPhase.Done)
                    onChanged()
                step
            }

            suspend fun answer(value: ProviderKey) = gate.withLock {
                val current =
                    identifier()
                        ?: throw SubscriptionOperationException("subscriptions/attempt-finished")
                request { source.answerLogin(current, value) }
            }

            suspend fun cancel() = gate.withLock {
                val current = identifier() ?: return@withLock
                request { source.cancelLogin(current) }
                finish(current)
                Unit
            }

            fun close() {
                val current =
                    synchronized(attemptsLock) {
                        id?.also {
                            id = null
                            attempts.remove(this)
                        }
                    } ?: return
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            withTimeout(10_000) {
                                gate.withLock {
                                    if (
                                        source.mux.state.value
                                            .takeIf { it.state == ConnectionState.Connected }
                                            ?.generation == lease
                                    )
                                        source.cancelLogin(current)
                                }
                            }
                        } catch (_: Exception) {
                            // Teardown has no active login UI; explicit cancellation reports
                            // errors.
                        }
                    }
                }
            }

            override fun toString() = "SubscriptionLogin"
        }

        override fun close() {
            synchronized(attemptsLock) { attempts.toList() }.forEach { it.close() }
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

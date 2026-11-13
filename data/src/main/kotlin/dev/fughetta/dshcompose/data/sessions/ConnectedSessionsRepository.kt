package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.commands.CommandCatalogs
import dev.fughetta.dshcompose.data.domainOperation
import dev.fughetta.dshcompose.data.outputStream
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.SessionOperationException
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

internal fun EngineSessionsRepository.connectedRepository(
    current: EngineSessionsRepository.Owner,
): SessionsRepository =
    object : SessionsRepository {
        private fun commands(): CommandCatalogs.Catalog {
            requireCurrent(current)
            return current.commands.connection
                ?: throw CancellationException("Command connection retired")
        }

        override suspend fun commandCatalog(sessionId: String) = commands().catalog(sessionId)

        override suspend fun executeCommand(sessionId: String, line: String) =
            commands().execute(sessionId, line)

        override fun selectCommandSession(sessionId: String?) {
            current.commands.connection?.selectSession(sessionId)
        }

        override fun invalidateCommands() {
            current.commands.connection?.invalidate()
        }

        override suspend fun <T : AutoCloseable, R> transferArchive(
            session: String,
            destination: suspend () -> T,
            output: (T) -> dev.fughetta.dshcompose.domain.ByteSink,
            commit: suspend (T) -> R,
            current: () -> Boolean,
            progress: (Long, Long?) -> Unit,
        ): SessionArchiveReceipt<R>? =
            transferSessionArchive(this, session, destination, output, commit, current, progress)

        private val mutableConnection = MutableStateFlow<SessionsRepository?>(this)
        override val connection = mutableConnection.asStateFlow()
        override val state = current.state.asStateFlow()
        override val hostHome
            get() = current.generation?.home

        override val generation
            get() = current.generation

        init {
            current.scope.coroutineContext.job.invokeOnCompletion {
                mutableConnection.value = null
                current.state.value = current.state.value.copy(available = false, loading = false)
            }
        }

        override val active: Boolean
            get() = synchronized(lock) { isCurrent(current) }

        override fun get(id: String): SessionSummary? =
            synchronized(lock) {
                requireCurrent(current)
                current.inventory.get(id)
            }

        override suspend fun refresh() = refresh(current)

        override suspend fun list(): List<SessionSummary> = execute({ listSessions() })

        override val supported: Boolean
            get() = active && current.client.canExportSessionLog

        override suspend fun exportLog(
            sessionId: String,
            destination: dev.fughetta.dshcompose.domain.ByteSink,
            onProgress: suspend (Long, Long?) -> Unit,
        ): Long = owned {
            val client = current.client
            client.exportSessionLog(sessionId, destination.outputStream(), onProgress)
        }

        override fun pending(eventId: String): Boolean =
            synchronized(lock) {
                active && current.client.mux.pendingRequests.value.contains(eventId)
            }

        override fun remaining(sessionId: String, callId: String) = flow {
            synchronized(lock) { requireCurrent(current) }
            val client = current.client
            emitAll(
                client.claimQuestionWait(sessionId, callId).map { event ->
                    synchronized(lock) { requireCurrent(current) }
                    (event as? MuxEvent.Item)?.let { item ->
                        ((item.value as? JsonObject)?.get("remainingMs") as? JsonPrimitive)
                            ?.longOrNull
                    }
                },
            )
        }

        override val context
            get() =
                synchronized(lock) {
                    requireCurrent(current)
                    search.context
                }

        override val catalogRevision
            get() =
                synchronized(lock) {
                    requireCurrent(current)
                    search.catalogRevision
                }

        override suspend fun results(query: String, hits: List<SessionContentHit>) = owned {
            search.results(query, hits)
        }

        override val searchActive: Boolean
            get() = active && search.active

        override suspend fun search(query: String): SessionSearchPage = owned {
            search.search(query)
        }

        override fun indexSessions(rows: PersistentList<SessionSummary>, archived: Set<String>) =
            synchronized(lock) {
                requireCurrent(current)
                search.inventory(rows, archived)
            }

        private suspend fun <T> owned(action: suspend () -> T): T {
            synchronized(lock) { requireCurrent(current) }
            val request = current.scope.async { action() }
            return try {
                domainOperation {
                    request.await().also { synchronized(lock) { requireCurrent(current) } }
                }
            } finally {
                request.cancel()
            }
        }

        private suspend fun <T> execute(
            operation: suspend ProtocolClient.() -> T,
            accepted: (T) -> Unit = {},
        ): T {
            try {
                val result = owned { current.client.operation() }
                synchronized(lock) {
                    requireCurrent(current)
                    accepted(result)
                    publish(current)
                }
                return result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw SessionOperationException(sessionDeletionFailure(error), error)
            }
        }

        override suspend fun create(cwd: String?, workspaceId: String?, agentPreset: String?) =
            execute({ createSession(cwd, workspaceId, agentPreset) })

        override suspend fun renameOffline(sessionId: String, title: String): String =
            throw ConnectionClosedException()

        override fun scheduleRefresh() {
            synchronized(lock) {
                requireCurrent(current)
                this@connectedRepository.scheduleRefresh()
            }
        }

        override suspend fun rename(sessionId: String, title: String) =
            execute({ renameSession(sessionId, title) }) { current.rename(sessionId, it) }

        override suspend fun forkSession(sessionId: String, atSeq: Long?) =
            execute({ forkSession(sessionId, atSeq) })

        override suspend fun forkState(sessionId: String) = execute({ readForkState(sessionId) })

        override suspend fun updateQueuedPrompt(
            sessionId: String,
            itemId: String,
            change: QueueChange,
        ) = execute({ changeQueue(sessionId, itemId, change) })

        override suspend fun createdAt(sessionId: String): Long? {
            try {
                val request =
                    synchronized(lock) {
                        requireCurrent(current)
                        current.creationTimes.getOrPut(sessionId) {
                            current.scope.async(start = CoroutineStart.LAZY) {
                                current.creationReads.withPermit {
                                    execute({ readCreatedAt(sessionId) })
                                }
                            }
                        }
                    }
                request.start()
                val result = request.await()
                synchronized(lock) { requireCurrent(current) }
                return result
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionOperationException) {
                throw error
            } catch (error: Exception) {
                throw SessionOperationException(sessionDeletionFailure(error), error)
            }
        }

        override suspend fun delete(sessionId: String) =
            execute({ deleteSessionPermanently(sessionId) }) {
                current.retired.add(sessionId)
                current.remove(sessionId)
            }
    }

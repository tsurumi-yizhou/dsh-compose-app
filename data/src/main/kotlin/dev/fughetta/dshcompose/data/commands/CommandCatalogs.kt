package dev.fughetta.dshcompose.data.commands

import dev.fughetta.dshcompose.data.skills.EngineSkillsRepository
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*

/** A consumer's cancelled await does not discard the connection-owned catalog request. */
internal class CommandCatalogs {
    private var client: ProtocolClient? = null
    private var generation: ConnectionGeneration? = null
    var connection: Catalog? = null
        private set

    private var owner: Catalog? = null

    fun connect(client: ProtocolClient?, parent: CoroutineScope) {
        val generation = client?.mux?.state?.value?.generation
        if (this.client === client && this.generation == generation && owner?.active == true) return
        this.client = client
        this.generation = generation
        owner?.close()
        owner = client?.takeIf { generation != null }?.let { Catalog(it, parent) }
        connection = owner
    }

    fun close() {
        owner?.close()
        owner = null
        connection = null
        client = null
        generation = null
    }

    internal class Catalog(private val client: ProtocolClient, parent: CoroutineScope) {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val lock = Any()
        private var session: String? = null
        private var cached: Deferred<CommandCatalog>? = null
        private val generation = client.mux.state.value.generation
        val active
            get() =
                scope.isActive &&
                    generation != null &&
                    client.mux.state.value.generation == generation

        private fun current() {
            if (!active) throw CancellationException("Command connection retired")
        }

        suspend fun catalog(sessionId: String): CommandCatalog {
            current()
            val pending =
                synchronized(lock) {
                    current()
                    if (session != sessionId || cached?.isCancelled == true) {
                        cached?.cancel()
                        cached = null
                    }
                    session = sessionId
                    cached
                        ?: scope
                            .async(start = CoroutineStart.LAZY) {
                                current()
                                try {
                                    withTimeout(20_000) { readCatalog(sessionId) }
                                        .also { current() }
                                } catch (error: TimeoutCancellationException) {
                                    throw CommandOperationException("commands/read-failed", error)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: HostRemoteException) {
                                    throw CommandOperationException(error.code, error)
                                } catch (error: Exception) {
                                    throw CommandOperationException("commands/read-failed", error)
                                }
                            }
                            .also { cached = it }
                }
            return pending.await().also { current() }
        }

        private suspend fun readCatalog(sessionId: String): CommandCatalog = coroutineScope {
            val commands = async { client.listCommands(sessionId) }
            val skills = async {
                EngineSkillsRepository(client)
                    .list(sessionId)
                    .map {
                        SlashCommand(it.name, it.name, it.description, "", SlashCommandKind.Skill)
                    }
                    .toPersistentList()
            }
            CommandCatalog(commands.await(), skills.await())
        }

        suspend fun execute(sessionId: String, line: String): CommandResult {
            current()
            val task = scope.async {
                current()
                try {
                    client.executeCommand(sessionId, line).also { current() }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: HostRemoteException) {
                    throw CommandOperationException(error.code, error)
                } catch (error: Exception) {
                    throw CommandOperationException("commands/execute-failed", error)
                }
            }
            return try {
                task.await()
            } finally {
                task.cancel()
            }
        }

        fun selectSession(sessionId: String?) =
            synchronized(lock) {
                if (session != sessionId) {
                    cached?.cancel()
                    cached = null
                    session = sessionId
                }
            }

        fun invalidate() =
            synchronized(lock) {
                cached?.cancel()
                cached = null
            }

        fun close() {
            scope.cancel()
        }
    }
}

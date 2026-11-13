package dev.fughetta.dshcompose.data.sessions.search

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One index per inventory snapshot; remote requests belong to the selected connection. */
class EngineSessionSearchRepository(
    private val scope: CoroutineScope,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Any()
    private val mutableContext = MutableStateFlow(SearchContext())
    val context = mutableContext.asStateFlow()
    private val revision = MutableStateFlow(0L)
    val catalogRevision = revision.asStateFlow()
    private var rows = persistentListOf<SessionSummary>()
    private var archived: Set<String> = emptySet()
    private var index: Deferred<SessionSearchIndex>? = null
    private var client: ProtocolClient? = null
    private var generation: ConnectionGeneration? = null
    private var owner: Connection? = null
    private var connectionCount = 0L
    val active: Boolean
        get() = owner?.active == true

    suspend fun search(query: String): SessionSearchPage =
        owner?.search(query)
            ?: throw SessionSearchException("search/unavailable", unavailable = true)

    fun connect(client: ProtocolClient?, host: String, parent: CoroutineScope) {
        val generation = client?.mux?.state?.value?.generation
        if (
            this.client === client &&
                this.generation == generation &&
                context.value.host == host &&
                (client == null || owner?.active == true)
        )
            return
        owner?.close()
        this.client = client
        this.generation = generation
        if (context.value.host != host) inventory(persistentListOf(), emptySet())
        owner = client?.takeIf { generation != null }?.let { Connection(it, parent) }
        connectionCount++
        mutableContext.value = SearchContext(host, if (owner == null) 0 else connectionCount)
    }

    fun inventory(rows: PersistentList<SessionSummary>, archived: Set<String>) =
        synchronized(lock) {
            if (this.rows === rows && this.archived == archived) return@synchronized
            if (this.rows !== rows) {
                index?.cancel()
                index = null
                this.rows = rows
            }
            this.archived = archived.toSet()
            revision.value++
        }

    suspend fun results(
        query: String,
        hits: List<SessionContentHit>,
    ): SessionSearchResults {
        val (pending, archived) =
            synchronized(lock) {
                val source = rows
                val pending =
                    index
                        ?: scope
                            .async(computation, start = CoroutineStart.LAZY) {
                                SessionSearchIndex(source)
                            }
                            .also { index = it }
                pending to this.archived
            }
        val ready = pending.await()
        return withContext(computation) { ready.search(query, archived, hits) }
    }

    fun close() {
        owner?.close()
        owner = null
        client = null
        generation = null
        inventory(persistentListOf(), emptySet())
        mutableContext.value = SearchContext(context.value.host)
    }

    private class Connection(private val client: ProtocolClient, parent: CoroutineScope) {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val generation = client.mux.state.value.generation
        val active
            get() =
                scope.isActive &&
                    generation != null &&
                    client.mux.state.value.generation == generation

        private fun current() {
            if (!active) throw CancellationException("Search connection retired")
        }

        suspend fun search(query: String): SessionSearchPage {
            current()
            val task = scope.async {
                current()
                try {
                    withTimeout(20_000) { client.searchSessions(query) }.also { current() }
                } catch (error: TimeoutCancellationException) {
                    throw SessionSearchException("search/read-failed", cause = error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: HostRemoteException) {
                    val unavailable =
                        error.code in
                            setOf(
                                "gateway/method-unavailable",
                                "gateway/service-unavailable",
                                "gateway/lookup-unavailable",
                                "METHOD_NOT_ALLOWED",
                            ) ||
                            error.code == "gateway/internal" &&
                                error.explanation?.contains(
                                    "session search is disabled",
                                    ignoreCase = true,
                                ) == true
                    throw SessionSearchException(error.code, unavailable, error)
                } catch (error: SessionSearchException) {
                    throw error
                } catch (error: Exception) {
                    throw SessionSearchException("search/read-failed", cause = error)
                }
            }
            return try {
                task.await()
            } finally {
                task.cancel()
            }
        }

        fun close() {
            scope.cancel()
        }
    }
}

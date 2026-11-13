package dev.fughetta.dshcompose.data.workspace

import dev.fughetta.dshcompose.data.link.requireGeneration
import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.data.workspaces.chooseWorkspaceDirectory
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.workspaces.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspacesRepository
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** WorkspacesRepository backed by one engine connection. */
class EngineWorkspacesRepository(
    private val client: ProtocolClient,
    private val connectionActive: () -> Boolean = { true },
) : WorkspacesRepository {
    private val registry = WorkspaceRegistry()

    override val inventory: WorkspaceInventory
        get() =
            synchronized(registry) {
                WorkspaceInventory(registry.snapshot(), registry.archived, registry.pinned)
            }

    private fun current() =
        connectionGeneration != null &&
            connectionActive() &&
            client.mux.state.value.let {
                it.generation == connectionGeneration &&
                    it.state == dev.fughetta.dshcompose.domain.hosts.ConnectionState.Connected
            }

    private suspend fun <T> mutation(
        acknowledge: (T, WorkspaceRegistry.Receipt) -> Unit,
        request: suspend () -> T,
    ): T {
        val receipt = synchronized(registry) { registry.beginMutation() }
        val result = request()
        synchronized(registry) {
            client.requireGeneration(connectionGeneration, connectionActive)
            acknowledge(result, receipt)
        }
        return result
    }

    override suspend fun <T> changeCollection(
        items: List<T>,
        request: suspend (WorkspacesRepository, T) -> WorkspaceEvent,
        onApplied: () -> Unit,
    ): Boolean? {
        var failed = false
        for (item in items) {
            if (!current()) return null
            val receipt = synchronized(registry) { registry.beginMutation() }
            try {
                val result = request(this, item)
                if (!current()) return null
                synchronized(registry) {
                    when (result) {
                        is WorkspaceEvent.Order -> registry.acknowledgeOrder(result.ids, receipt)
                        is WorkspaceEvent.Pinned -> registry.acknowledgePinned(result.ids, receipt)
                        is WorkspaceEvent.Archived ->
                            registry.acknowledgeArchived(result.ids, receipt)
                        else -> error("Not a workspace collection result")
                    }
                }
                onApplied()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            }
        }
        return if (current()) !failed else null
    }

    override suspend fun changeSession(
        id: String,
        operation: WorkspaceSessionOperation,
        stopActivity: Boolean,
    ): WorkspaceEvent =
        withContext(Dispatchers.Default) {
            when (operation) {
                WorkspaceSessionOperation.Pin -> WorkspaceEvent.Pinned(pin(id, true))
                WorkspaceSessionOperation.Unpin -> WorkspaceEvent.Pinned(pin(id, false))
                WorkspaceSessionOperation.Archive ->
                    WorkspaceEvent.Archived(archive(id, stopActivity).toPersistentSet())
                WorkspaceSessionOperation.Restore ->
                    WorkspaceEvent.Archived(unarchive(id).toPersistentSet())
            }
        }

    override suspend fun chooseDirectory(
        files: WorkspaceFilesRepository,
        sessions: dev.fughetta.dshcompose.domain.sessions.SessionsRepository,
        path: String,
        register: Boolean,
        registerWorkspace: (suspend (WorkspacesRepository, String) -> Workspace)?,
    ): DirectorySelection =
        chooseWorkspaceDirectory(
            DirectoryPickerPorts(files, this, sessions),
            path,
            register,
            registerWorkspace,
        )

    private val connectionGeneration = client.mux.state.value.generation
    override val generation: Any?
        get() = connectionGeneration

    private fun workspaceFromJson(raw: JsonElement): Workspace {
        val value = raw.objectValue()
        return Workspace(
            value.requiredString("workspaceId"),
            value.requiredString("path"),
            value.string("title").orEmpty(),
            value.ids("sessionIds").toPersistentSet(),
            value.string("updatedAt"),
        )
    }

    private fun JsonObject.ids(key: String) =
        (get(key) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toPersistentList()

    override fun events(): Flow<WorkspaceEvent> = flow {
        client.requireGeneration(connectionGeneration, connectionActive)
        var awaitingSnapshot = true
        client.mux.stream("workspace/follow").collect { event ->
            client.requireGeneration(connectionGeneration, connectionActive)
            when (event) {
                is MuxEvent.Reset -> {
                    awaitingSnapshot = true
                    emit(WorkspaceEvent.Reset(event.generation))
                }
                is MuxEvent.Item -> {
                    val frame = event.value.objectValue()
                    val update =
                        when (frame.string("type")) {
                            "baseline",
                            "replace" -> {
                                val value = frame["value"]!!.objectValue()
                                awaitingSnapshot = false
                                WorkspaceEvent.Snapshot(
                                    (value["items"] as? JsonArray)
                                        .orEmpty()
                                        .map(::workspaceFromJson)
                                        .toPersistentList(),
                                    value.ids("archivedSessionIds").toPersistentSet(),
                                    value.ids("pinnedSessionIds"),
                                )
                            }
                            "upsert" ->
                                WorkspaceEvent.Upsert(workspaceFromJson(frame["workspace"]!!))
                            "remove" -> WorkspaceEvent.Remove(frame.requiredString("workspaceId"))
                            "order" -> WorkspaceEvent.Order(frame.ids("workspaceIds"))
                            "archived" ->
                                WorkspaceEvent.Archived(
                                    frame.ids("archivedSessionIds").toPersistentSet(),
                                )
                            "pinned" -> WorkspaceEvent.Pinned(frame.ids("pinnedSessionIds"))
                            else -> null
                        }
                    if (awaitingSnapshot && update != null)
                        throw HostProtocolException("Missing workspace snapshot")
                    update?.let { emit(it) }
                }
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .onEach { event ->
            synchronized(registry) {
                client.requireGeneration(connectionGeneration, connectionActive)
                registry.apply(event)
            }
        }

    override suspend fun create(path: String): Workspace =
        mutation({ value, receipt -> registry.acknowledge(value, receipt) }) {
            client.withGeneration(connectionGeneration, connectionActive) {
                withContext(Dispatchers.Default) {
                    client.connection
                        .call(
                            "workspace/create",
                            buildJsonObject { putJsonObject("request") { put("path", path) } },
                        )
                        .objectValue()["workspace"]!!
                        .let(::workspaceFromJson)
                }
            }
        }

    override suspend fun rename(id: String, title: String): Workspace =
        mutation({ value, receipt -> registry.acknowledge(value, receipt) }) {
            client.withGeneration(connectionGeneration, connectionActive) {
                withContext(Dispatchers.Default) {
                    client.connection
                        .call(
                            "workspace/rename",
                            buildJsonObject {
                                putJsonObject("request") {
                                    put("workspaceId", id)
                                    put("title", title)
                                }
                            },
                        )
                        .objectValue()["workspace"]!!
                        .let(::workspaceFromJson)
                }
            }
        }

    override suspend fun delete(id: String): Unit =
        mutation({ _, receipt -> registry.acknowledgeRemoval(id, receipt) }) {
            client.withGeneration(connectionGeneration, connectionActive) {
                client.connection.call(
                    "workspace/delete",
                    buildJsonObject { putJsonObject("request") { put("workspaceId", id) } },
                )
                Unit
            }
        }

    override suspend fun move(id: String, beforeId: String?): PersistentList<String> =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                client.connection
                    .call(
                        "workspace/insertBefore",
                        buildJsonObject {
                            putJsonObject("request") {
                                put("workspaceId", id)
                                beforeId?.let { put("beforeWorkspaceId", it) }
                            }
                        },
                    )
                    .objectValue()
                    .ids("workspaceIds")
            }
        }

    override suspend fun pin(sessionId: String, pinned: Boolean): PersistentList<String> =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                client.connection
                    .call(
                        if (pinned) "workspace/pinSession" else "workspace/unpinSession",
                        buildJsonObject {
                            putJsonObject("request") { put("sessionId", sessionId) }
                        },
                    )
                    .objectValue()
                    .ids("pinnedSessionIds")
            }
        }

    override suspend fun archive(sessionId: String, stopActivity: Boolean): List<String> =
        client.withGeneration(connectionGeneration, connectionActive) {
            client.archiveSession(sessionId, stopActivity)
        }

    override suspend fun unarchive(sessionId: String): List<String> =
        client.withGeneration(connectionGeneration, connectionActive) {
            client.unarchiveSession(sessionId)
        }
}

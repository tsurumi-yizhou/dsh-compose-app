package dev.fughetta.dshcompose.files

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.data.workspace.EngineWorkspacesRepository
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.settings.WorkspacePreferencesStore
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspacesViewModelTest {
    @Test
    fun batchAppliesEveryHostCollectionSequentiallyWithoutDialogOrOverlappingWrites() =
        runBlocking {
            withTimeout(10000) {
                val host = Host()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                host.beforeCall = { _, id ->
                    if (id == "one") {
                        started.complete(Unit)
                        release.await()
                    }
                }
                val states = mutableListOf<WorkspacesUiState>()
                val controller = controller(host) { states.add(it) }
                try {
                    ready(host, controller)
                    controller.manageSessions(listOf("one", "two"), WorkspaceSessionOperation.Pin)
                    started.await()
                    assertTrue(states.last().writing)
                    assertNull(states.last().sessionDialog)
                    controller.manageSessions(listOf("ignored"), WorkspaceSessionOperation.Archive)
                    release.complete(Unit)
                    host.state.first { !it.writing }
                    assertEquals(listOf("one", "two"), host.calls.map { it.second })
                    assertEquals(listOf("two", "one", "host-pinned"), host.state.value.pinned)
                    assertTrue(
                        states.any { it.writing && it.pinned == listOf("one", "host-pinned") },
                    )

                    host.beforeCall = { _, _ -> }
                    controller.manageSessions(listOf("one", "two"), WorkspaceSessionOperation.Unpin)
                    host.state.first { !it.writing }
                    assertEquals(listOf("host-pinned"), host.state.value.pinned)
                    controller.manageSessions(
                        listOf("one", "two"),
                        WorkspaceSessionOperation.Archive,
                    )
                    host.state.first { !it.writing }
                    assertEquals(setOf("one", "two", "host-archived"), host.state.value.archived)
                    controller.manageSessions(
                        listOf("one", "two"),
                        WorkspaceSessionOperation.Restore,
                    )
                    host.state.first { !it.writing }
                    assertEquals(setOf("host-archived"), host.state.value.archived)
                    assertEquals(
                        listOf("pinSession", "unpinSession", "archiveSession", "unarchiveSession")
                            .flatMap { method -> List(2) { "workspace/$method" } },
                        host.calls.map { it.first },
                    )
                    assertNull(host.state.value.error)
                    assertTrue(states.all { it.sessionDialog == null })
                } finally {
                    release.complete(Unit)
                    controller.close()
                    host.client.close()
                }
            }
        }

    @Test
    fun batchContinuesAfterFailureAndPublishesTheExistingSessionError() = runBlocking {
        withTimeout(10000) {
            val host = Host()
            host.beforeCall = { _, id ->
                if (id == "failed") throw HostRemoteException("session/not-found")
            }
            val controller = controller(host)
            try {
                ready(host, controller)
                controller.manageSessions(
                    listOf("one", "failed", "two"),
                    WorkspaceSessionOperation.Archive,
                )
                val result = host.state.first { !it.writing }
                assertEquals(listOf("one", "failed", "two"), host.calls.map { it.second })
                assertEquals(setOf("one", "two", "host-archived"), result.archived)
                assertEquals(UiR.string.workspace_session_failed.toString(), result.error)
                assertNull(result.sessionDialog)
            } finally {
                controller.close()
                host.client.close()
            }
        }
    }

    @Test
    fun singleRestoreStillRequiresConfirmationAndCallsOnRestored() = runBlocking {
        withTimeout(10000) {
            val host = Host()
            val restored = CompletableDeferred<String>()
            val controller = controller(host, onRestored = { id -> restored.complete(id) })
            try {
                ready(host, controller)
                controller.manageSession("host-archived", WorkspaceSessionOperation.Restore, true)
                assertTrue(host.calls.isEmpty())
                assertNotNull(host.state.value.sessionDialog)
                controller.confirmSession()
                assertEquals("host-archived", restored.await())
                assertTrue(host.state.value.archived.isEmpty())
                assertNull(host.state.value.sessionDialog)
            } finally {
                controller.close()
                host.client.close()
            }
        }
    }

    private fun CoroutineScope.controller(
        host: Host,
        onRestored: (String) -> Unit = {},
        publish: (WorkspacesUiState) -> Unit = {},
    ): WorkspacesViewModel {
        val data = MutableStateFlow<Preferences>(emptyPreferences())
        val store =
            object : DataStore<Preferences> {
                override val data = data.asStateFlow()

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                    transform(data.value).also { data.value = it }
            }
        return WorkspacesViewModel(
            this,
            { it.toString() },
            {
                host.state.value = it
                publish(it)
            },
            WorkspacePreferencesStore(store),
            onRestored,
        )
    }

    private suspend fun ready(host: Host, controller: WorkspacesViewModel) {
        host.client.mux.ready()
        controller.connect(EngineWorkspacesRepository(host.client), "host")
        host.state.first { it.connected && !it.loading && it.preferencesLoaded }
    }

    private class Host {
        val state = MutableStateFlow(WorkspacesUiState())
        val calls = mutableListOf<Pair<String, String>>()
        var beforeCall: suspend (String, String) -> Unit = { _, _ -> }
        private val pinned = mutableListOf("host-pinned")
        private val archived = mutableSetOf("host-archived")
        private val frames = Channel<JsonObject>(Channel.UNLIMITED)
        private val delegate = HostConnection("https://unused.test")
        private val link =
            MultiplexerLink(
                incoming = frames,
                sendFrame = send@{ frame ->
                        if (frame["op"]?.jsonPrimitive?.content != "subscribe") return@send true
                        val value =
                            when (frame.getValue("method").jsonPrimitive.content) {
                                "\$events" ->
                                    buildJsonObject {
                                        put("type", "ready")
                                        put("clientId", "host")
                                        putJsonObject("host") { put("home", "/home") }
                                    }
                                "workspace/follow" ->
                                    buildJsonObject {
                                        put("type", "replace")
                                        putJsonObject("value") {
                                            putJsonArray("items") {}
                                            put(
                                                "pinnedSessionIds",
                                                JsonArray(pinned.map(::JsonPrimitive)),
                                            )
                                            put(
                                                "archivedSessionIds",
                                                JsonArray(archived.map(::JsonPrimitive)),
                                            )
                                        }
                                    }
                                else -> error("Unexpected stream")
                            }
                        return@send frames
                            .trySend(
                                buildJsonObject {
                                    put("op", "event")
                                    put("id", frame.getValue("id"))
                                    put("data", value)
                                },
                            )
                            .isSuccess
                    },
                release = { frames.close() },
            )
        val client =
            ProtocolClient(
                object : HostTransport by delegate {
                    override suspend fun openMuxLink() = link

                    override suspend fun call(endpoint: String, args: JsonObject): JsonElement {
                        val request = args.getValue("request").jsonObject
                        val id = request.getValue("sessionId").jsonPrimitive.content
                        calls.add(endpoint to id)
                        beforeCall(endpoint, id)
                        when (endpoint) {
                            "workspace/pinSession" -> pinned.add(0, id)
                            "workspace/unpinSession" -> pinned.remove(id)
                            "workspace/archiveSession" -> {
                                assertTrue(request.getValue("stopActivity").jsonPrimitive.boolean)
                                archived.add(id)
                            }
                            "workspace/unarchiveSession" -> archived.remove(id)
                            else -> error("Unexpected mutation: $endpoint")
                        }
                        return buildJsonObject {
                            put("pinnedSessionIds", JsonArray(pinned.map(::JsonPrimitive)))
                            put("archivedSessionIds", JsonArray(archived.map(::JsonPrimitive)))
                        }
                    }
                },
            )
    }
}

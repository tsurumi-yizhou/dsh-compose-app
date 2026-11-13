package dev.fughetta.dshcompose.ui.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewmodel.initializer
import dev.fughetta.dshcompose.data.workspace.EngineWorkspacesRepository
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.files.WorkspacesViewModel
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.settings.WorkspacePreferencesStore
import dev.fughetta.dshcompose.ui.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSearchViewModelTest {
    @Test
    fun searchRetiresLateQueriesDefersImeAndOpensArchivedHitsOnlyAfterTheCurrentHostsAcknowledgement():
        Unit = runBlocking {
        withTimeout(15000) {
            val queries = Channel<String>(16)
            val calls = AtomicInteger()
            val oldRead = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val oldFinished = CompletableDeferred<Unit>()
            val restoreStarted = Channel<String>(2)
            val releaseRestore = CompletableDeferred<Unit>()
            val releaseStaleRestore = CompletableDeferred<Unit>()
            val staleRestoreFinished = CompletableDeferred<Unit>()
            val opens = AtomicInteger()
            val opened = CompletableDeferred<String>()
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                        install(WebSockets)
                        routing {
                            webSocket("/compose/events") {
                                for (raw in incoming) {
                                    val frame =
                                        Json.parseToJsonElement((raw as Frame.Text).readText())
                                            .jsonObject
                                    if (frame["op"]?.jsonPrimitive?.content != "subscribe") continue
                                    val endpoint = frame.getValue("method").jsonPrimitive.content
                                    val value =
                                        when (endpoint) {
                                            "\$events" ->
                                                buildJsonObject {
                                                    put("type", "ready")
                                                    put("clientId", "host")
                                                    putJsonObject("host") { put("home", "/home") }
                                                }
                                            "workspace/follow" ->
                                                buildJsonObject {
                                                    put("type", "baseline")
                                                    putJsonObject("value") {
                                                        putJsonArray("items") {}
                                                        putJsonArray("archivedSessionIds") {
                                                            add("archived")
                                                            add("archived2")
                                                        }
                                                        putJsonArray("pinnedSessionIds") {}
                                                    }
                                                }
                                            else ->
                                                error(
                                                    "Search must not subscribe to conversation history",
                                                )
                                        }
                                    send(
                                        buildJsonObject {
                                            put("op", "event")
                                            put("id", frame.getValue("id"))
                                            put("data", value)
                                        }
                                            .toString(),
                                    )
                                }
                            }
                            post("/compose/rpc") {
                                val input = Json.parseToJsonElement(call.receiveText()).jsonObject
                                val args =
                                    input.getValue("args").jsonObject.getValue("request").jsonObject
                                var unavailable = false
                                var late = false
                                val value =
                                    when (input.getValue("method").jsonPrimitive.content) {
                                        "session/search" -> {
                                            assertEquals(setOf("query"), args.keys)
                                            val query = args.getValue("query").jsonPrimitive.content
                                            calls.incrementAndGet()
                                            queries.send(query)
                                            if (query == "old") {
                                                oldRead.complete(Unit)
                                                withContext(NonCancellable) { releaseOld.await() }
                                                late = true
                                            }
                                            unavailable = query == "needle"
                                            buildJsonObject {
                                                putJsonArray("items") {
                                                    addJsonObject {
                                                        put(
                                                            "sessionId",
                                                            if (query == "old") "stale"
                                                            else "content",
                                                        )
                                                        put("snippet", "Text around $query")
                                                    }
                                                    addJsonObject {
                                                        put("sessionId", "archived")
                                                        put("snippet", "Archived $query")
                                                    }
                                                }
                                                put("hasMore", true)
                                            }
                                        }
                                        "workspace/unarchiveSession" -> {
                                            val id =
                                                args.getValue("sessionId").jsonPrimitive.content
                                            restoreStarted.send(id)
                                            withContext(NonCancellable) {
                                                if (id == "archived") releaseRestore.await()
                                                else releaseStaleRestore.await()
                                            }
                                            buildJsonObject {
                                                putJsonArray("archivedSessionIds") {
                                                    if (id == "archived") add("archived2")
                                                }
                                            }
                                        }
                                        else ->
                                            error("Search must not download conversation histories")
                                    }
                                try {
                                    call.respondText(
                                        buildJsonObject {
                                            put("id", input.getValue("id"))
                                            if (unavailable)
                                                putJsonObject("error") {
                                                    put("code", "gateway/internal")
                                                    put(
                                                        "message",
                                                        "session search is disabled synthetic-private",
                                                    )
                                                }
                                            else put("result", value)
                                        }
                                            .toString(),
                                        ContentType.Application.Json,
                                    )
                                } finally {
                                    if (late) oldFinished.complete(Unit)
                                    if (args["sessionId"]?.jsonPrimitive?.content == "archived2")
                                        staleRestoreFinished.complete(Unit)
                                }
                            }
                        }
                    }
                    .start(wait = false)
            val workspaceStates = MutableStateFlow(WorkspacesUiState())
            val preferences =
                object : DataStore<Preferences> {
                    override val data = MutableStateFlow<Preferences>(emptyPreferences())

                    override suspend fun updateData(
                        transform: suspend (Preferences) -> Preferences,
                    ): Preferences = transform(data.value).also { data.value = it }
                }
            Dispatchers.setMain(
                coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher,
            )
            val store = androidx.lifecycle.ViewModelStore()
            val engine =
                dev.fughetta.dshcompose.data.sessions.search.EngineSessionSearchRepository(
                    this,
                )
            val repository = SearchSessions(engine)
            val search =
                androidx.lifecycle
                    .ViewModelProvider(
                        store,
                        androidx.lifecycle.viewmodel.viewModelFactory {
                            initializer { SessionSearchViewModel(repository) }
                        },
                    )[SessionSearchViewModel::class.java]
            val states = search.uiState
            val workspaces =
                WorkspacesViewModel(
                    this,
                    { it.toString() },
                    { workspaceStates.value = it },
                    WorkspacePreferencesStore(preferences),
                    onRestored = { id ->
                        opens.incrementAndGet()
                        opened.complete(id)
                    },
                )
            val port = server.engine.resolvedConnectors().single().port
            val one = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            val two = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            val inventory =
                listOf("title", "content", "archived", "archived2", "stale")
                    .map { id ->
                        SessionSummary(
                            id,
                            if (id == "title") "Alpha project" else "Other $id",
                            "/work",
                            false,
                            false,
                            true,
                            null,
                            null,
                            0,
                        )
                    }
                    .toPersistentList()
            try {
                one.mux.ready()
                engine.connect(one, "host", this)
                engine.inventory(inventory, setOf("archived", "archived2"))
                states.first { it.epoch > 0 }
                search.observe(true)
                search.input("ol", null, null)
                search.input("old", null, null)
                oldRead.await()
                assertEquals("old", queries.receive())
                search.input("alpha", null, null)
                assertEquals("alpha", queries.receive())
                val found = states.first { it.status == ContentSearchStatus.Ready && !it.deriving }
                assertEquals(
                    listOf("title", "content"),
                    found.results.active.map { it.session.sessionId },
                )
                assertEquals("archived", found.results.archived.single().session.sessionId)
                assertTrue(found.hasMore)
                assertTrue(search.canOpen(found.epoch))
                releaseOld.complete(Unit)
                oldFinished.await()
                search.input("alpha 拼音", 6, 8)
                val composing = states.first { it.input == "alpha 拼音" && !it.deriving }
                assertEquals("alpha", composing.query)
                search.input("needle", null, null)
                assertEquals("needle", queries.receive())
                states.first { it.status == ContentSearchStatus.Unavailable && !it.deriving }
                search.input("alpha", null, null)
                val titles = states.first {
                    it.query == "alpha" &&
                        it.status == ContentSearchStatus.Unavailable &&
                        !it.deriving
                }
                assertEquals(listOf("title"), titles.results.active.map { it.session.sessionId })
                assertEquals(3, calls.get())
                two.mux.ready()
                engine.connect(two, "host", this)
                assertEquals("alpha", queries.receive())
                states.first { it.status == ContentSearchStatus.Ready && !it.deriving }
                assertEquals(4, calls.get())
                search.observe(false)
                assertFalse(search.canOpen(found.epoch))
                assertEquals("alpha", states.value.input)
                assertTrue(states.value.results.isEmpty)
                search.observe(true)
                assertEquals("alpha", queries.receive())
                states.first { it.status == ContentSearchStatus.Ready && !it.deriving }
                search.dismiss()
                assertTrue(states.value.input.isEmpty())
                workspaces.connect(EngineWorkspacesRepository(two), "host")
                workspaceStates.first { it.connected && !it.loading && "archived" in it.archived }
                workspaces.manageSession(
                    "archived",
                    WorkspaceSessionOperation.Restore,
                    openAfterRestore = true,
                )
                workspaces.confirmSession()
                assertEquals("archived", restoreStarted.receive())
                assertFalse(opened.isCompleted)
                releaseRestore.complete(Unit)
                assertEquals("archived", opened.await())
                assertFalse("archived" in workspaceStates.value.archived)
                workspaces.manageSession(
                    "archived2",
                    WorkspaceSessionOperation.Restore,
                    openAfterRestore = true,
                )
                workspaces.confirmSession()
                assertEquals("archived2", restoreStarted.receive())
                workspaces.connect(null, "other-host")
                engine.connect(null, "other-host", this)
                releaseStaleRestore.complete(Unit)
                staleRestoreFinished.await()
                assertEquals(1, opens.get())
                assertTrue(states.value.input.isEmpty())
                assertTrue(states.value.results.isEmpty)
                search.observe(false)
            } finally {
                releaseOld.complete(Unit)
                releaseRestore.complete(Unit)
                releaseStaleRestore.complete(Unit)
                store.clear()
                engine.close()
                Dispatchers.resetMain()
                workspaces.close()
                one.close()
                two.close()
                server.stop(0, 1000)
            }
        }
    }

    private class SearchSessions(
        private val search:
            dev.fughetta.dshcompose.data.sessions.search.EngineSessionSearchRepository,
    ) : dev.fughetta.dshcompose.testing.TestSessionsRepository() {
        override val context
            get() = search.context

        override val catalogRevision
            get() = search.catalogRevision

        override suspend fun results(
            query: String,
            hits: List<dev.fughetta.dshcompose.domain.sessions.search.SessionContentHit>,
        ) = search.results(query, hits)

        override val searchActive
            get() = search.active

        override suspend fun search(query: String) = search.search(query)

        override fun indexSessions(
            rows:
                kotlinx.collections.immutable.PersistentList<
                    dev.fughetta.dshcompose.domain.sessions.SessionSummary,
                >,
            archived: Set<String>,
        ) = search.inventory(rows, archived)
    }
}

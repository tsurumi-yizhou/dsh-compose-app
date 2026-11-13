package dev.fughetta.dshcompose.ui.agents

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.data.sessions.EngineAgentTeamsRepository
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.AppAction
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
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
class AgentsViewModelIntegrationTest {
    @Test
    fun teamReadsMessagesAndConfirmedMutationsStayWithTheirConnection(): Unit = runBlocking {
        withTimeout(20000) {
            val enabled = AtomicBoolean(false)
            val profileReads = AtomicInteger()
            val slow = AtomicBoolean(false)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                        install(WebSockets)
                        routing {
                            webSocket("/compose/events") {
                                val frame =
                                    Json.parseToJsonElement(
                                            (incoming.receive() as Frame.Text).readText(),
                                        )
                                        .jsonObject
                                send(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", frame.getValue("id"))
                                        putJsonObject("data") {
                                            put("type", "ready")
                                            put(
                                                "clientId",
                                                call.request.headers[HttpHeaders.Host]!!,
                                            )
                                            putJsonObject("host") { put("home", "/work") }
                                        }
                                    }
                                        .toString(),
                                )
                                awaitCancellation()
                            }
                            post("/compose/rpc") {
                                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                                val method = body.getValue("method").jsonPrimitive.content
                                val args = body["args"]?.jsonObject
                                val other = call.request.headers[HttpHeaders.Host] == "other.test"
                                val id =
                                    ((args?.get("request") as? JsonObject)?.get("sessionId")
                                            as? JsonPrimitive)
                                        ?.content
                                val delayed =
                                    !other &&
                                        method == "session/projections" &&
                                        id == "lead" &&
                                        slow.compareAndSet(true, false)
                                if (delayed) {
                                    started.complete(Unit)
                                    withContext(NonCancellable) { release.await() }
                                }
                                var error: String? = null
                                val value: JsonElement =
                                    when (method) {
                                        "session/projections" -> {
                                            if (id == "unreadable") error = "session/not-found"
                                            Json.parseToJsonElement(
                                                """{"asOfSeq":10,"values":{"subagentCatalog":[{"id":"worker","mode":"continuable"}],"agentTeam":{"members":[{"id":"lead","name":"${if (other) "Other" else "Lead"}","role":"lead"},{"id":"worker","name":"Worker"},{"id":"unreadable","name":"Unreadable"}],"tasks":[{"id":"active","subject":"Work","status":"pending"},{"id":"gone","status":"deleted"}]}}}""",
                                            )
                                        }
                                        "pluginManager/listBundles" -> {
                                            profileReads.incrementAndGet()
                                            Json.parseToJsonElement(
                                                """[{"name":"@deepseek-ai/dsh-experimental-agent-team-profile","enabled":${enabled.get()}}]""",
                                            )
                                        }
                                        "pluginManager/setBundleEnabled" -> {
                                            enabled.set(true)
                                            Json.parseToJsonElement(
                                                """{"application":"restart-required","changed":true,"enabled":true}""",
                                            )
                                        }
                                        "session/page" -> {
                                            val request = args!!.getValue("request").jsonObject
                                            val address = request.getValue("address").jsonObject
                                            val child =
                                                address["childSessionId"]?.jsonPrimitive?.content
                                            if (child != null) {
                                                check(
                                                    address
                                                        .getValue("parentSessionId")
                                                        .jsonPrimitive
                                                        .content == "lead",
                                                )
                                                check(
                                                    address
                                                        .getValue("mode")
                                                        .jsonPrimitive
                                                        .content == "continuable",
                                                )
                                            }
                                            Json.parseToJsonElement(
                                                """{"records":[{"type":"user/message","time":${if (child != null) 20 else 10},"seq":3,"data":{"source":{"kind":"team-message","senderName":"Sender"},"content":[{"type":"text","text":"${child ?: "lead"} message"}]}}]}""",
                                            )
                                        }
                                        "subagents/interruptByParent" -> {
                                            error = "subagent/not-resumable"
                                            JsonNull
                                        }
                                        else -> error("Unexpected team endpoint: $method")
                                    }
                                try {
                                    call.respondText(
                                        buildJsonObject {
                                            put("id", body.getValue("id"))
                                            if (error != null)
                                                putJsonObject("error") {
                                                    put("code", error)
                                                    put("message", "Synthetic failure")
                                                }
                                            else put("result", value)
                                        }
                                            .toString(),
                                        ContentType.Application.Json,
                                    )
                                } finally {
                                    if (delayed) finished.complete(Unit)
                                }
                            }
                        }
                    }
                    .start(wait = false)
            Dispatchers.setMain(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            val store = ViewModelStore()
            val repository = EngineAgentTeamsRepository()
            val opened = Channel<Pair<AgentTeamsRepository, String>>(8)
            val catalog =
                object : dev.fughetta.dshcompose.testing.TestSessionsRepository() {
                    override val state =
                        MutableStateFlow(
                            SessionCatalogState(
                                available = true,
                                allSessions =
                                    persistentListOf(
                                        SessionSummary(
                                            "lead",
                                            "Lead",
                                            null,
                                            false,
                                            false,
                                            true,
                                            null,
                                            null,
                                            0,
                                        ),
                                    ),
                            ),
                        )

                    override fun get(id: String) =
                        state.value.allSessions.firstOrNull { it.sessionId == id }

                    override suspend fun refresh() {}
                }
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer {
                            AgentsViewModel(
                                repository.connection,
                                catalog,
                                { it },
                                { "restart" },
                                { owner, id -> opened.trySend(owner to id) },
                            )
                        }
                    },
                )[AgentsViewModel::class.java]
            val port = server.engine.resolvedConnectors().single().port
            val one = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            val two =
                ProtocolClient(HostConnection("http://127.0.0.1:$port", hostHeader = "other.test"))
            fun action(value: AgentsAction) {
                model.handle(AppAction.Agents(value))
            }
            try {
                one.mux.ready()
                repository.connect(one, this, "one")
                val original = repository.connection.value!!
                model.handle(AppAction.OpenAgents("lead"))
                action(AgentsAction.Observe(true))
                val initial =
                    model.uiState.first { !it.loading && it.team != null && it.nodes.isNotEmpty() }
                assertEquals(listOf("active"), initial.team!!.tasks.map { it.id })
                assertTrue(initial.bundleAvailable)
                assertFalse(initial.bundleEnabled)
                original.profile()
                assertEquals(1, profileReads.get())
                action(AgentsAction.Tab(AgentsTab.Messages))
                val messages =
                    model.uiState.first { it.messages.size == 2 && it.unreadable.isNotEmpty() }
                assertEquals(
                    listOf("worker message", "lead message"),
                    messages.messages.map { it.text },
                )
                assertEquals(setOf("unreadable"), messages.unreadable)
                val route = initial.nodes.single().address
                action(AgentsAction.Open(route))
                assertEquals(original to "worker", opened.receive())
                action(AgentsAction.Interrupt(route))
                model.uiState.first { !it.busy && it.error == "subagent/not-resumable" }
                action(AgentsAction.EnableTeam)
                model.uiState.first {
                    !it.busy && !it.loading && it.bundleEnabled && it.notice == "restart"
                }
                assertEquals(2, profileReads.get())
                slow.set(true)
                action(AgentsAction.Refresh)
                started.await()
                two.mux.ready()
                repository.connect(two, this, "two")
                model.handle(AppAction.OpenAgents("lead"))
                model.uiState.first {
                    !it.loading && it.team?.members?.firstOrNull()?.name == "Other"
                }
                release.complete(Unit)
                finished.await()
                assertEquals("Other", model.uiState.value.team!!.members.first().name)
                assertNull(model.uiState.value.error)
                try {
                    original.interrupt(route)
                    fail("Retired connection accepted a mutation")
                } catch (_: CancellationException) {}
            } finally {
                release.complete(Unit)
                val connections = coroutineContext.job.children.toList()
                val modelJob = model.viewModelScope.coroutineContext.job
                store.clear()
                repository.close()
                one.close()
                two.close()
                server.stop(0, 1000)
                withContext(NonCancellable) {
                    modelJob.join()
                    connections.joinAll()
                }
                Dispatchers.resetMain()
            }
        }
    }
}

package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.goals.EngineGoalsRepository
import dev.fughetta.dshcompose.data.jobs.EngineJobsRepository
import dev.fughetta.dshcompose.data.messages.AttachmentConnections
import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.presets.EngineAgentPresetsRepository
import dev.fughetta.dshcompose.data.providers.EngineProvidersRepository
import dev.fughetta.dshcompose.data.providers.EngineSessionModelsRepository
import dev.fughetta.dshcompose.data.providers.EngineSubscriptionsRepository
import dev.fughetta.dshcompose.data.schedules.EngineSchedulesRepository
import dev.fughetta.dshcompose.data.sessions.EngineAgentTeamsRepository
import dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository
import dev.fughetta.dshcompose.data.settings.AppSettingsRepository
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(
    sdk = [28],
    application = android.app.Application::class,
    manifest = org.robolectric.annotation.Config.NONE,
)
class SelectedConnectionTest {
    private fun client(
        id: String,
        request: suspend (String, JsonObject) -> JsonElement = { endpoint, _ ->
            error("Unexpected request: $endpoint")
        },
    ): ProtocolClient {
        val delegate = HostConnection("https://unused.test")
        return ProtocolClient(
            object : HostTransport by delegate {
                override suspend fun call(endpoint: String, args: JsonObject): JsonElement =
                    request(endpoint, args)

                override suspend fun openMuxLink(): MultiplexerLink {
                    val input = Channel<JsonObject>(Channel.UNLIMITED)
                    return MultiplexerLink(
                        input,
                        sendFrame = { frame ->
                            if (frame["op"] == JsonPrimitive("subscribe"))
                                input.trySend(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", frame.getValue("id"))
                                        putJsonObject("data") {
                                            put("type", "ready")
                                            put("clientId", id)
                                            putJsonObject("host") { put("home", "/home/$id") }
                                        }
                                    },
                                )
                            true
                        },
                        release = { input.close() },
                    )
                }
            },
        )
    }

    @Test
    fun reconnectReplacesHandlesBeforePublishingReadyAndHostSwitchRetiresOldSubscriptions() =
        runBlocking {
            withTimeout(5000) {
                val sent = mutableListOf<String>()
                val readingUsage = CompletableDeferred<Unit>()
                val finishUsage = CompletableDeferred<Unit>()
                val first =
                    client("first") { endpoint, args ->
                        when (endpoint) {
                            "session/prompt" ->
                                sent +=
                                    args
                                        .getValue("request")
                                        .jsonObject
                                        .getValue("requestId")
                                        .jsonPrimitive
                                        .content
                            "session/projections" -> {
                                readingUsage.complete(Unit)
                                finishUsage.await()
                            }
                            else -> error("Unexpected request: $endpoint")
                        }
                        JsonNull
                    }
                val second = client("second")
                val linkScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
                val linkScope =
                    CoroutineScope(
                        SupervisorJob() +
                            kotlinx.coroutines.test.StandardTestDispatcher(linkScheduler),
                    )
                val links = HostConnections(linkScope)
                val abandoned = client("abandoned")
                links.register("abandoned") { abandoned }
                links.register("first") { first }
                links.register("second") { second }
                val settings = AppSettingsRepository(applicationScope = this)
                val schedules = EngineSchedulesRepository()
                val jobs = EngineJobsRepository()
                val goals = EngineGoalsRepository()
                val sessions = EngineSessionsRepository(cacheScope = this)
                val media =
                    dev.fughetta.dshcompose.data.media.EngineMediaRepository(
                        org.robolectric.RuntimeEnvironment.getApplication(),
                    )
                val selected =
                    SelectedConnection(
                        this,
                        AttachmentConnections(),
                        EngineMessagesRepository(this),
                        EngineSessionModelsRepository(),
                        EngineProvidersRepository(),
                        EngineSubscriptionsRepository(),
                        EngineAgentPresetsRepository(),
                        EngineAgentTeamsRepository(),
                        sessions,
                        settings,
                        schedules,
                        goals,
                        jobs,
                        media,
                    )
                val pending = dev.fughetta.dshcompose.data.messages.EngineMessagesRepository(this)
                val outbox =
                    dev.fughetta.dshcompose.data.sync.PendingPromptOutbox(
                        dev.fughetta.dshcompose.data.sync.FakeOutbox(),
                    )
                val delivery =
                    dev.fughetta.dshcompose.data.messages.PendingPromptDelivery(
                        this,
                        selected.view,
                        pending,
                        outbox,
                    )
                try {
                    val initial = first.mux.ready()
                    val oldSession = selected.open(links, "first") { true }!!
                    assertNull(selected.open(links, "first") { false })
                    assertSame(oldSession, selected.session)
                    assertNull(selected.open(links, "abandoned") { false })
                    linkScheduler.advanceUntilIdle()
                    assertNull(links.link("abandoned"))
                    assertSame(first, links.link("first"))
                    selected.selectSession("chat")
                    selected.state.first { it.generation == initial }
                    val offline =
                        dev.fughetta.dshcompose.domain.messages.PendingPrompt(
                            requestId = "offline-request",
                            host = "first",
                            home = initial.home,
                            sessionId = "background-chat",
                            text = "hello",
                            composerText = "hello",
                            quote = null,
                            files = kotlinx.collections.immutable.persistentListOf(),
                            state =
                                dev.fughetta.dshcompose.domain.messages.PendingPromptState
                                    .Uncertain,
                        )
                    outbox.retain(offline)
                    val delivered =
                        async(start = CoroutineStart.UNDISPATCHED) { delivery.delivered.first() }
                    delivery.restore("first", initial.home)
                    assertEquals("offline-request", delivered.await().prompt.requestId)
                    assertTrue(outbox.outstanding("first").isEmpty())
                    delivery.resume()
                    yield()
                    assertEquals(listOf("offline-request"), sent)
                    val oldRepositories = oldSession.repositories!!
                    val lateUsage = async {
                        runCatching { oldRepositories.usage.projections("chat") }
                    }
                    readingUsage.await()
                    val oldSessions = sessions.connection.value!!
                    val oldSettings = settings.connection.value!!
                    val oldSchedules = schedules.connection.value!!
                    val oldJobs = jobs.connection.value!!
                    val oldMedia = media.connection.value!!
                    first.mux.reconnect()
                    val reconnected = first.mux.ready()
                    assertNotEquals(initial, reconnected)
                    selected.state.first { it.generation == reconnected }
                    val requests = oldSession.requests.first { it.generation == reconnected }
                    assertTrue(requests.pending.isEmpty())
                    finishUsage.complete(Unit)
                    assertTrue(lateUsage.await().exceptionOrNull() is ConnectionClosedException)
                    val reconnectedRepositories = oldSession.repositories!!
                    assertNotSame(oldRepositories, reconnectedRepositories)
                    assertNotSame(oldRepositories.files, reconnectedRepositories.files)
                    assertEquals(initial, oldRepositories.files.generation)
                    assertEquals(reconnected, reconnectedRepositories.files.generation)
                    try {
                        oldRepositories.files.listing("chat", ".")
                        fail("Retired files must not send a request")
                    } catch (_: ConnectionClosedException) {}
                    assertNotSame(oldSettings, settings.connection.value)
                    assertNotSame(oldSchedules, schedules.connection.value)
                    assertNotSame(oldJobs, jobs.connection.value)
                    assertFalse(oldSchedules.active)
                    assertFalse(oldJobs.active)
                    assertFalse(oldMedia.active)
                    assertNotSame(oldMedia, media.connection.value)
                    assertNotSame(oldSessions, sessions.connection.value)
                    assertEquals(initial, oldSessions.generation)
                    assertEquals(reconnected, sessions.connection.value!!.generation)
                    assertFalse(oldSessions.active)
                    try {
                        oldSessions.updateQueuedPrompt("chat", "queued")
                        fail("Retired catalog must not send a request")
                    } catch (
                        error: dev.fughetta.dshcompose.domain.sessions.SessionOperationException,) {
                        assertTrue(error.cause is ConnectionClosedException)
                    }
                    try {
                        oldSettings.describe()
                        fail("Retired settings must not send a request")
                    } catch (_: CancellationException) {}
                    assertEquals("chat", goals.state.value?.sessionId)

                    val replacement = second.mux.ready()
                    val currentSession = selected.open(links, "second") { true }!!
                    selected.state.first { it.generation == replacement }
                    val currentSettings = settings.connection.value
                    assertFalse(reconnectedRepositories.notifications.active)
                    try {
                        reconnectedRepositories.files.stat("chat", "file")
                        fail("Deselected repositories must not send a request")
                    } catch (_: ConnectionClosedException) {}
                    val waiting = offline.copy(requestId = "old-host-request")
                    outbox.retain(waiting)
                    delivery.restore("first", initial.home)
                    delivery.retry(waiting.owner, waiting.requestId)
                    assertEquals(listOf("offline-request"), sent)
                    assertEquals("old-host-request", outbox.outstanding("first").single().requestId)
                    oldSession.close()
                    first.mux.reconnect()
                    first.mux.ready()
                    yield()
                    assertEquals(replacement, selected.state.value.generation)
                    assertSame(currentSettings, settings.connection.value)
                    assertTrue(currentSession.scope.isActive)
                    assertFalse(oldSession.scope.isActive)

                    selected.disconnect()
                    assertNull(currentSession.repositories)
                    assertNull(settings.connection.value)
                    assertNull(schedules.connection.value)
                    assertNull(jobs.connection.value)
                    assertNull(media.connection.value)
                    assertNull(sessions.connection.value)
                    assertFalse(currentSession.scope.isActive)
                    assertEquals(ConnectionState.Connected, second.mux.state.value.state)
                } finally {
                    delivery.close()
                    pending.close()
                    selected.close()
                    sessions.close()
                    linkScope.cancel()
                    abandoned.close()
                    first.close()
                    second.close()
                }
            }
        }
}

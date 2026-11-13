package dev.fughetta.dshcompose.ui.providers

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.data.providers.EngineSubscriptionsRepository
import dev.fughetta.dshcompose.domain.providers.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelIntegrationTest {
    @Test
    fun closingDuringBeginCancelsTheLateAttemptAndTheNextLoginCompletesThroughTheEngine() =
        runBlocking {
            val beginStarted = CompletableDeferred<Unit>()
            val beginAck = CompletableDeferred<Unit>()
            val answerStarted = CompletableDeferred<Unit>()
            val answerAck = CompletableDeferred<Unit>()
            val begins = AtomicInteger()
            val cancellations = AtomicInteger()
            val answers = AtomicInteger()
            val signedIn = AtomicBoolean()
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                        install(WebSockets)
                        routing {
                            webSocket("/compose/events") {
                                val open =
                                    Json.parseToJsonElement(
                                            (incoming.receive() as Frame.Text).readText(),
                                        )
                                        .jsonObject
                                send(
                                    buildJsonObject {
                                        put("op", "event")
                                        put("id", open.getValue("id"))
                                        putJsonObject("data") {
                                            put("type", "ready")
                                            put("clientId", "host1")
                                            putJsonObject("host") { put("home", "/root") }
                                        }
                                    }
                                        .toString(),
                                )
                                awaitCancellation()
                            }
                            post("/compose/rpc") {
                                val input = Json.parseToJsonElement(call.receiveText()).jsonObject
                                val args = input.getValue("args").jsonObject
                                val result =
                                    when (input.getValue("method").jsonPrimitive.content) {
                                        "appAuth/list" ->
                                            buildJsonObject {
                                                putJsonArray("providers") {
                                                    addJsonObject {
                                                        put("id", "openai-codex")
                                                        put("signedIn", signedIn.get())
                                                    }
                                                }
                                            }
                                        "appAuth/begin" -> {
                                            assertEquals(
                                                "openai-codex",
                                                args.getValue("provider").jsonPrimitive.content,
                                            )
                                            val number = begins.incrementAndGet()
                                            if (number == 1) {
                                                beginStarted.complete(Unit)
                                                beginAck.await()
                                            }
                                            buildJsonObject { put("attemptId", "attempt$number") }
                                        }
                                        "appAuth/cancel" -> {
                                            assertEquals(
                                                "attempt1",
                                                args.getValue("attemptId").jsonPrimitive.content,
                                            )
                                            cancellations.incrementAndGet()
                                            JsonObject(emptyMap())
                                        }
                                        "appAuth/poll" -> {
                                            assertEquals(
                                                "attempt2",
                                                args.getValue("attemptId").jsonPrimitive.content,
                                            )
                                            buildJsonObject {
                                                put(
                                                    "phase",
                                                    if (signedIn.get()) "done" else "prompt",
                                                )
                                                put("message", "Synthetic authorization prompt")
                                            }
                                        }
                                        "appAuth/answer" -> {
                                            assertEquals(
                                                "attempt2",
                                                args.getValue("attemptId").jsonPrimitive.content,
                                            )
                                            assertEquals(
                                                "synthetic-auth-code",
                                                args.getValue("value").jsonPrimitive.content,
                                            )
                                            answers.incrementAndGet()
                                            answerStarted.complete(Unit)
                                            answerAck.await()
                                            signedIn.set(true)
                                            JsonObject(emptyMap())
                                        }
                                        "appAuth/signOut" -> {
                                            assertEquals(
                                                "openai-codex",
                                                args.getValue("provider").jsonPrimitive.content,
                                            )
                                            signedIn.set(false)
                                            JsonObject(emptyMap())
                                        }
                                        else -> error("Unexpected auth request")
                                    }
                                call.respondText(
                                    buildJsonObject {
                                        put("id", input.getValue("id"))

                                        put("result", result)
                                    }
                                        .toString(),
                                    ContentType.Application.Json,
                                )
                            }
                        }
                    }
                    .start(wait = false)
            Dispatchers.setMain(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            val store = ViewModelStore()
            var changed = 0
            val repository = EngineSubscriptionsRepository { changed++ }
            val controller =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer { SubscriptionsViewModel(repository.connection) }
                    },
                )[SubscriptionsViewModel::class.java]
            val states = controller.uiState
            val port = server.engine.resolvedConnectors().single().port
            val client = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            try {
                withTimeout(10000) {
                    client.mux.ready()
                    repository.connect(client, this@runBlocking)
                    controller.handle(AppAction.ObserveSubscriptions(true))
                    states.first { !it.loading && it.statuses.isNotEmpty() }
                    controller.handle(AppAction.BeginSubscriptionLogin(Subscription.ChatGpt))
                    beginStarted.await()
                    val oldNonce = states.value.login!!.nonce
                    controller.handle(AppAction.CloseSubscriptionLogin(oldNonce))
                    beginAck.complete(Unit)
                    states.first { it.login == null && !it.writing }
                    assertEquals(1, cancellations.get())

                    controller.handle(AppAction.BeginSubscriptionLogin(Subscription.ChatGpt))
                    states.first { it.login?.step?.phase == LoginPhase.Prompt }
                    controller.handle(AppAction.CloseSubscriptionLogin(oldNonce))
                    assertNotNull(states.value.login)
                    val secret = ProviderKey.from("synthetic-auth-code")
                    val owned = secret.useCharacters { it }
                    controller.handle(AppAction.AnswerSubscriptionLogin(secret))
                    answerStarted.await()
                    controller.handle(AppAction.ObserveSubscriptions(false))
                    answerAck.complete(Unit)
                    states.first { !it.writing }
                    controller.handle(AppAction.ObserveSubscriptions(true))
                    states.first {
                        it.login?.step?.phase == LoginPhase.Done &&
                            it.statuses.any { status -> status.signedIn }
                    }
                    assertTrue(owned.all { it == '\u0000' })
                    assertEquals(1, answers.get())
                    assertEquals(1, changed)
                    assertFalse(states.value.login.toString().contains("synthetic-auth-code"))
                    controller.handle(AppAction.CloseSubscriptionLogin(states.value.login!!.nonce))
                    states.first { it.login == null && !it.writing }
                    controller.handle(AppAction.RequestSubscriptionSignOut(Subscription.ChatGpt))
                    assertTrue(signedIn.get())
                    controller.handle(AppAction.ConfirmSubscriptionSignOut)
                    states.first {
                        !it.loading && !it.writing && it.statuses.none { status -> status.signedIn }
                    }
                    assertEquals(2, changed)
                    assertNull(states.value.signOut)
                    assertEquals(1, cancellations.get())
                }
            } finally {
                beginAck.complete(Unit)
                answerAck.complete(Unit)
                store.clear()
                repository.close()
                Dispatchers.resetMain()
                client.close()
                server.stop(0, 1000)
            }
        }
}

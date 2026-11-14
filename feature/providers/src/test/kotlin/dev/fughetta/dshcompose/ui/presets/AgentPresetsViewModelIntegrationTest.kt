package dev.fughetta.dshcompose.ui.presets

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.data.presets.EngineAgentPresetsRepository
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.protocol.*
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentPresetsViewModelIntegrationTest {
    @Test
    fun defaultsDocumentsCreatorHandoffAndRemovalFollowAcknowledgementsAndStayWithTheirHost():
        Unit = runBlocking {
        withTimeout(20000) {
            val methods = CopyOnWriteArrayList<String>()
            val prompts = CopyOnWriteArrayList<String>()
            val created = AtomicInteger()
            val removals = AtomicInteger()
            val selectedDefault = MutableStateFlow("standard")
            val revision = AtomicInteger(7)
            val slowStandard = AtomicBoolean(true)
            val standardStarted = CompletableDeferred<Unit>()
            val releaseStandard = CompletableDeferred<Unit>()
            val standardFinished = CompletableDeferred<Unit>()
            val failPrompt = AtomicBoolean()
            val slowDefault = AtomicBoolean()
            val defaultStarted = CompletableDeferred<Unit>()
            val releaseDefault = CompletableDeferred<Unit>()
            val defaultFinished = CompletableDeferred<Unit>()
            fun yaml(id: String) =
                """
                - id: persona
                  name: '@deepseek-ai/dsh-persona'
                  config:
                    prefix: $id prompt
                    includeRuntimeContext: false
                - id: tool-web
                  name: '@deepseek-ai/dsh-tool-web'
                - id: tool-pwsh
                  name: '@deepseek-ai/dsh-tool-pwsh'
                  disabled: !!js process.platform !== 'win32'
            """
                    .trimIndent()
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
                                methods.add(method)
                                val args = body.getValue("args").jsonObject
                                val other = call.request.headers[HttpHeaders.Host] == "other.test"
                                var failed = false
                                var lateStandard = false
                                var lateDefault = false
                                val value =
                                    when (method) {
                                        "agentPresets/list" ->
                                            buildJsonObject {
                                                putJsonArray("presets") {
                                                    if (other)
                                                        addJsonObject {
                                                            put("id", "other")
                                                            put("name", "Other Host")
                                                            put("isDefault", true)
                                                        }
                                                    else {
                                                        for ((id, order) in
                                                            listOf(
                                                                "custom" to 20,
                                                                "cordis" to 10,
                                                                "standard" to 0,
                                                            )) addJsonObject {
                                                            put("id", id)
                                                            put("name", "$id name")
                                                            put("order", order)
                                                            put(
                                                                "isDefault",
                                                                selectedDefault.value == id,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        "pluginManager/listBundles" ->
                                            buildJsonArray {
                                                if (!other) {
                                                    for ((name, official) in
                                                        listOf(
                                                            "@local/custom" to false,
                                                            "@official/readonly" to true,
                                                        )) addJsonObject {
                                                        put("name", name)
                                                        put("official", official)
                                                        put("removable", true)
                                                        put("enabled", true)
                                                        put("installed", true)
                                                        putJsonArray("rows") {
                                                            addJsonObject {
                                                                put("rowId", "preset-custom")
                                                                put(
                                                                    "moduleName",
                                                                    AGENT_PRESET_MODULE,
                                                                )
                                                            }
                                                            if (official)
                                                                addJsonObject {
                                                                    put("entryId", "anonymous-row")
                                                                }
                                                        }
                                                    }
                                                }
                                            }
                                        "settings/describe" ->
                                            buildJsonObject {
                                                put("writable", true)
                                                putJsonArray("namespaces") {
                                                    addJsonObject {
                                                        put("ns", "agent-preset-registry")
                                                        put("revision", revision.get())
                                                        putJsonObject("value") {
                                                            put(
                                                                "selectedDefault",
                                                                selectedDefault.value,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        "settings/update" -> {
                                            assertEquals(
                                                "agent-preset-registry",
                                                args.getValue("ns").jsonPrimitive.content,
                                            )
                                            assertEquals(
                                                revision.get(),
                                                args.getValue("expectedRevision").jsonPrimitive.int,
                                            )
                                            val patch = args.getValue("patch").jsonObject
                                            assertEquals(setOf("selectedDefault"), patch.keys)
                                            if (slowDefault.get()) {
                                                lateDefault = true
                                                defaultStarted.complete(Unit)
                                                withContext(NonCancellable) {
                                                    releaseDefault.await()
                                                }
                                            }
                                            selectedDefault.value =
                                                patch
                                                    .getValue("selectedDefault")
                                                    .jsonPrimitive
                                                    .content
                                            revision.incrementAndGet()
                                            JsonNull
                                        }
                                        "agentPresets/read" -> {
                                            val id =
                                                args.getValue("agentPreset").jsonPrimitive.content
                                            if (id == "standard" && slowStandard.getAndSet(false)) {
                                                lateStandard = true
                                                standardStarted.complete(Unit)
                                                withContext(NonCancellable) {
                                                    releaseStandard.await()
                                                }
                                            }
                                            buildJsonObject {
                                                put("agentPreset", id)
                                                put("content", yaml(id))
                                            }
                                        }
                                        "session/create" -> {
                                            assertEquals(
                                                JsonObject(emptyMap()),
                                                args.getValue("request"),
                                            )
                                            buildJsonObject {
                                                put(
                                                    "sessionId",
                                                    "created-${created.incrementAndGet()}",
                                                )
                                            }
                                        }
                                        "agentPresets/select" -> {
                                            assertEquals(
                                                "created-${created.get()}",
                                                args.getValue("agentId").jsonPrimitive.content,
                                            )
                                            assertEquals(
                                                "cordis",
                                                args.getValue("agentPreset").jsonPrimitive.content,
                                            )
                                            JsonPrimitive("cordis")
                                        }
                                        "session/prompt" -> {
                                            val request = args.getValue("request").jsonObject
                                            assertEquals(
                                                "created-${created.get()}",
                                                request.getValue("sessionId").jsonPrimitive.content,
                                            )
                                            assertFalse(
                                                request
                                                    .getValue("clientTimeZone")
                                                    .jsonPrimitive
                                                    .content
                                                    .isBlank(),
                                            )
                                            prompts.add(
                                                request
                                                    .getValue("content")
                                                    .jsonArray
                                                    .single()
                                                    .jsonObject
                                                    .getValue("text")
                                                    .jsonPrimitive
                                                    .content,
                                            )
                                            failed = failPrompt.get()
                                            buildJsonObject { put("accepted", true) }
                                        }
                                        "pluginManager/removeBundle" -> {
                                            assertEquals(
                                                "@local/custom",
                                                args.getValue("name").jsonPrimitive.content,
                                            )
                                            removals.incrementAndGet()
                                            buildJsonObject {
                                                put("changed", true)
                                                put("application", "restart-required")
                                                put("restartReason", "no-hmr")
                                            }
                                        }
                                        else -> error("Unexpected preset endpoint")
                                    }
                                try {
                                    call.respondText(
                                        buildJsonObject {
                                            put("id", body.getValue("id"))
                                            if (failed)
                                                putJsonObject("error") {
                                                    put("code", "gateway/internal")
                                                    put(
                                                        "message",
                                                        "Synthetic uncertain handoff",
                                                    )
                                                }
                                            else put("result", value)
                                        }
                                            .toString(),
                                        ContentType.Application.Json,
                                    )
                                } finally {
                                    if (lateStandard) standardFinished.complete(Unit)
                                    if (lateDefault) defaultFinished.complete(Unit)
                                }
                            }
                        }
                    }
                    .start(wait = false)
            Dispatchers.setMain(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            val store = ViewModelStore()
            val repository = EngineAgentPresetsRepository()
            val opened = Channel<String>(8)
            val controller =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer {
                            AgentPresetsViewModel(
                                repository.connection,
                                { it.toString() },
                                { "Copy $it" },
                                { bundle, replacing ->
                                    "Write ${bundle.definition.id} replacing=$replacing\n\n"
                                },
                                { _, id -> opened.trySend(id) },
                            )
                        }
                    },
                )[AgentPresetsViewModel::class.java]
            val state = controller.uiState
            val port = server.engine.resolvedConnectors().single().port
            val one = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            val two =
                ProtocolClient(HostConnection("http://127.0.0.1:$port", hostHeader = "other.test"))
            try {
                one.mux.ready()
                repository.connect(one, this)
                controller.handle(AppAction.ObserveAgentPresets(true))
                val roster = state.first { !it.loading && it.presets.isNotEmpty() }
                assertEquals(listOf("custom", "cordis", "standard"), roster.presets.map { it.id })
                assertEquals("@local/custom", agentPresetOwner("custom", roster.bundles)?.name)
                controller.handle(AppAction.SetDefaultAgentPreset("custom"))
                state.first { !it.writing && it.presets.first().isDefault }
                controller.handle(AppAction.RemoveAgentPreset("custom"))
                assertNull(state.value.removal)
                controller.handle(AppAction.SetDefaultAgentPreset("standard"))
                state.first { !it.writing && it.presets.last().isDefault }
                controller.handle(AppAction.ViewAgentPreset("custom"))
                assertEquals(
                    yaml("custom"),
                    state.first { it.detail?.loading == false }.detail?.document?.content,
                )
                controller.handle(AppAction.CloseAgentPresetDetail)
                controller.handle(AppAction.OpenPresetEditor(PresetEditMode.Create, null))
                standardStarted.await()
                controller.handle(AppAction.EditPresetField(PresetField.Name, "Read Only Review"))
                controller.handle(AppAction.ChoosePresetBase("custom"))
                val editor =
                    state
                        .first { it.editor?.composition != null && it.editor?.loading == false }
                        .editor!!
                assertEquals("read-only-review", editor.values.id)
                assertEquals("custom prompt", editor.values.prompt)
                releaseStandard.complete(Unit)
                standardFinished.await()
                controller.handle(AppAction.ClosePresetEditor)
                assertTrue(state.value.editor!!.discard)
                controller.handle(AppAction.KeepPresetEditor)
                controller.handle(AppAction.TogglePresetRow("tool-web", false))
                controller.handle(
                    AppAction.EditPresetField(
                        PresetField.Prompt,
                        "Read only.\nDo not write files.",
                    ),
                )
                controller.handle(AppAction.ReplacePresetPrompt(true))
                controller.handle(AppAction.SubmitPreset)
                assertEquals("created-1", opened.receive())
                state.first { !it.writing }
                assertEquals(
                    listOf("session/create", "agentPresets/select", "session/prompt"),
                    methods.filter {
                        it in setOf("session/create", "agentPresets/select", "session/prompt")
                    },
                )
                assertTrue(prompts.single().contains("@local/dsh-preset-read-only-review"))
                assertTrue(prompts.single().contains("            disabled: true"))
                assertTrue(prompts.single().contains("process.platform !== 'win32'"))
                controller.handle(AppAction.OpenPresetEditor(PresetEditMode.Edit, "custom"))
                state.first { it.editor?.loading == false }
                controller.handle(AppAction.EditPresetField(PresetField.Id, "cannot-change"))
                assertEquals("custom", state.value.editor!!.values.id)
                failPrompt.set(true)
                controller.handle(AppAction.SubmitPreset)
                state.first { !it.writing && it.editor?.error != null }
                assertEquals("created-2", state.value.editor?.createdSessionId)
                controller.handle(AppAction.SubmitPreset)
                assertEquals(2, prompts.size)
                assertTrue(prompts.last().contains("replacing=@local/custom"))
                assertTrue(prompts.last().contains("\"name\": \"@local/custom\""))
                controller.handle(AppAction.OpenPresetCreatorSession)
                assertEquals("created-2", opened.receive())
                controller.handle(AppAction.RemoveAgentPreset("custom"))
                controller.handle(AppAction.ConfirmRemoveAgentPreset)
                val removed = state.first { !it.writing && it.removal == null && it.notice != null }
                assertEquals(1, removals.get())
                assertTrue(removed.presets.any { it.id == "custom" })
                slowDefault.set(true)
                controller.handle(AppAction.SetDefaultAgentPreset("custom"))
                defaultStarted.await()
                two.mux.ready()
                repository.connect(two, this)
                state.first { !it.loading && it.presets.singleOrNull()?.id == "other" }
                releaseDefault.complete(Unit)
                defaultFinished.await()
                assertEquals("other", state.value.presets.single().id)
                assertNull(state.value.notice)
                assertNull(state.value.editor)
            } finally {
                releaseStandard.complete(Unit)
                releaseDefault.complete(Unit)
                store.clear()
                repository.close()
                Dispatchers.resetMain()
                one.close()
                two.close()
                server.stop(0, 1000)
            }
        }
    }
}

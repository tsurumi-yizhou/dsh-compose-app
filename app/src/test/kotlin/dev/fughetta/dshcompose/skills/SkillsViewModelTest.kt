package dev.fughetta.dshcompose.skills

import dev.fughetta.dshcompose.data.skills.EngineSkillsRepository
import dev.fughetta.dshcompose.domain.skills.SkillsPorts
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.testing.ClientSessions
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillsViewModelTest {
    @Test
    fun catalogFilteringAndDraftInsertionKeepTheWorkspaceAndWaitForCreationAcknowledgement(): Unit =
        runBlocking {
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                withContext(dispatcher) {
                    withTimeout(20000) {
                        val creates = Channel<JsonObject>(4)
                        val createReplies = Channel<Unit>(4)
                        val slowRead = Channel<Unit>(2)
                        val readReplies = Channel<Unit>(2)
                        val methods = CopyOnWriteArrayList<String>()
                        val server =
                            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                                    routing {
                                        post("/compose/rpc") {
                                            val input =
                                                Json.parseToJsonElement(call.receiveText())
                                                    .jsonObject
                                            val method =
                                                input.getValue("method").jsonPrimitive.content
                                            methods.add(method)
                                            val request =
                                                input
                                                    .getValue("args")
                                                    .jsonObject
                                                    .getValue("request")
                                                    .jsonObject
                                            val value =
                                                when (method) {
                                                    "skills/list" -> {
                                                        val id =
                                                            request
                                                                .getValue("sessionId")
                                                                .jsonPrimitive
                                                                .content
                                                        if (id == "slow") {
                                                            slowRead.send(Unit)
                                                            readReplies.receive()
                                                        }
                                                        buildJsonObject {
                                                            putJsonArray("skills") {
                                                                fun skill(
                                                                    name: String,
                                                                    description: String,
                                                                    manual: Boolean = false,
                                                                    path: String? = null,
                                                                ) = buildJsonObject {
                                                                    put("name", name)
                                                                    put("description", description)
                                                                    put(
                                                                        "whenToUse",
                                                                        "review changes",
                                                                    )
                                                                    put("modelInvocable", !manual)
                                                                    path?.let { put("path", it) }
                                                                }
                                                                add(
                                                                    skill(
                                                                        "workspace",
                                                                        "工作区技能",
                                                                        true,
                                                                        "/work/source/.dsh/skills/workspace/SKILL.md",
                                                                    ),
                                                                )
                                                                add(
                                                                    skill(
                                                                        "plugin",
                                                                        "Global plugin skill",
                                                                        path =
                                                                            "/home/user/.dsh/plugins/plugin/SKILL.md",
                                                                    ),
                                                                )
                                                                add(skill("plugin", "duplicate"))
                                                                add(skill("", "invalid"))
                                                                for (index in 0 until 10000) add(
                                                                    skill(
                                                                        "catalog$index",
                                                                        "Other skill $index",
                                                                    ),
                                                                )
                                                            }
                                                        }
                                                    }
                                                    "session/create" -> {
                                                        creates.send(request)
                                                        createReplies.receive()
                                                        buildJsonObject {
                                                            put("sessionId", "created")
                                                        }
                                                    }
                                                    else -> error("Unexpected mutation: $method")
                                                }
                                            call.respondText(
                                                buildJsonObject {
                                                    put("id", input.getValue("id"))

                                                    put("result", value)
                                                }
                                                    .toString(),
                                                ContentType.Application.Json,
                                            )
                                        }
                                    }
                                }
                                .start()
                        val port = server.engine.resolvedConnectors().single().port
                        val client = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
                        val other =
                            ProtocolClient(
                                HostConnection("http://127.0.0.1:$port", hostHeader = "other.test"),
                            )
                        val updates = MutableStateFlow<SkillsUiState?>(null)
                        val scope = CoroutineScope(SupervisorJob() + dispatcher)
                        val drafts = mutableMapOf("source" to "ask @foo.txt next")
                        val spans =
                            persistentListOf(ReferenceSpan(4, 12, "file", ReferenceSpanKind.File))
                        val opened = mutableListOf<String>()
                        val selected = mutableListOf<String>()
                        var canCreate = false
                        var canUseHere = true
                        val workspaces =
                            listOf(
                                Workspace(
                                    "w1",
                                    "/work/source",
                                    "Source",
                                    persistentSetOf("source"),
                                    null,
                                ),
                                Workspace(
                                    "w2",
                                    "/work/selected",
                                    "Selected",
                                    persistentSetOf(),
                                    null,
                                ),
                            )
                        val ports = java.util.IdentityHashMap<ProtocolClient, SkillsPorts>()
                        fun portsOf(value: ProtocolClient) =
                            ports.getOrPut(value) {
                                SkillsPorts(EngineSkillsRepository(value), ClientSessions(value))
                            }
                        val controller =
                            SkillsViewModel(
                                scope,
                                { "message:$it" },
                                context = {
                                    SkillUseContext(workspaces, "w2", canCreate, canUseHere)
                                },
                                publish = { updates.value = it },
                                insert = { owner, id, skill ->
                                    assertSame(portsOf(client), owner)
                                    val old = drafts[id].orEmpty()
                                    val result =
                                        insertComposerSkill(
                                            old,
                                            if (id == "source") 7 else null,
                                            skill.name,
                                            if (id == "source") spans else persistentListOf(),
                                        )
                                    drafts[id] = result.input.text
                                    true
                                },
                                openSession = { _, id -> opened.add(id) },
                                selectWorkspace = { selected.add(it) },
                            )
                        suspend fun ready() =
                            updates.filterNotNull().first { !it.loading && !it.filtering }
                        try {
                            controller.connect(portsOf(client))
                            controller.open("source")
                            controller.observe(true)
                            val initial = ready()
                            assertEquals(10002, initial.skills.size)
                            assertEquals(1, initial.skills.count { it.name == "plugin" })
                            controller.query("ｇｌｏｂａｌ PLUGIN")
                            assertEquals(listOf("plugin"), ready().skills.map { it.name })
                            controller.query("工作区")
                            assertEquals(listOf("workspace"), ready().skills.map { it.name })
                            controller.view("workspace")
                            assertFalse(updates.value!!.selected!!.modelInvocable)
                            controller.use(false)
                            assertNull(updates.value)
                            assertEquals("ask @foo.txt /workspace next", drafts["source"])
                            assertEquals(listOf("source"), opened)
                            assertFalse(methods.contains("session/create"))
                            controller.open("source")
                            controller.observe(true)
                            ready()
                            controller.view("workspace")
                            controller.use(true)
                            assertFalse(updates.value!!.busy)
                            assertNotNull(updates.value!!.error)
                            canCreate = true
                            controller.use(true)
                            val create = creates.receive()
                            assertEquals("w1", create.getValue("workspaceId").jsonPrimitive.content)
                            assertTrue(updates.value!!.busy)
                            assertTrue(selected.isEmpty())
                            assertNull(drafts["created"])
                            controller.closeDialog()
                            assertNotNull(updates.value)
                            createReplies.send(Unit)
                            updates.first { it == null }
                            assertEquals("/workspace ", drafts["created"])
                            assertEquals(listOf("w1"), selected)
                            assertEquals(listOf("source", "created"), opened)
                            controller.open("source")
                            controller.observe(true)
                            ready()
                            controller.view("plugin")
                            canUseHere = false
                            controller.use(false)
                            assertNotNull(updates.value)
                            assertEquals(2, opened.size)
                            controller.use(true)
                            assertEquals(
                                "w2",
                                creates.receive().getValue("workspaceId").jsonPrimitive.content,
                            )
                            controller.connect(portsOf(other))
                            createReplies.send(Unit)
                            assertNull(updates.value)
                            assertEquals(2, opened.size)
                            controller.connect(portsOf(client))
                            controller.open("slow")
                            controller.observe(true)
                            slowRead.receive()
                            controller.connect(portsOf(other))
                            controller.open("source")
                            controller.observe(true)
                            ready()
                            readReplies.send(Unit)
                            controller.query("PLUGIN")
                            val current = ready()
                            assertEquals("source", current.sessionId)
                            assertEquals(listOf("plugin"), current.skills.map { it.name })
                            controller.observe(false)
                            assertTrue(updates.value!!.skills.isEmpty())
                            controller.observe(true)
                            assertEquals(listOf("plugin"), ready().skills.map { it.name })
                            controller.closeDialog()
                            assertNull(updates.value)
                            assertEquals(2, methods.count { it == "session/create" })
                            assertTrue(methods.all { it in setOf("skills/list", "session/create") })
                        } finally {
                            controller.close()
                            scope.cancel()
                            client.close()
                            other.close()
                            server.stop(0, 1000)
                        }
                    }
                }
            } finally {
                dispatcher.close()
            }
        }
}

package dev.fughetta.dshcompose.permissions

import dev.fughetta.dshcompose.data.permissions.EnginePermissionsRepository
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PermissionsViewModelTest {
    @Test
    fun permissionChangesRequireConsentKeepTheirRevisionAndOnlyShowTheEnginesReadback(): Unit =
        runBlocking {
            withTimeout(15000) {
                val current = AtomicReference("workspace-write")
                val default = AtomicReference("workspace-write")
                val revision = AtomicInteger(5)
                val commands = AtomicInteger()
                val writes = AtomicInteger()
                val entered = Channel<Unit>(4)
                val proceed = Channel<Unit>(4)
                var holdWrite = false
                var rejectCommand = false
                var failReadback = false
                var writable = true
                fun client(other: Boolean = false): ProtocolClient =
                    ProtocolClient(
                        HostConnection(
                            "http://localhost",
                            engine =
                                MockEngine { request ->
                                    val body =
                                        Json.parseToJsonElement((request.body as TextContent).text)
                                            .jsonObject
                                    val args = body.getValue("args").jsonObject
                                    var code: String? = null
                                    val value =
                                        when (body.getValue("method").jsonPrimitive.content) {
                                            "permissionPresets/catalog" ->
                                                buildJsonObject {
                                                    fun option(value: String) = buildJsonObject {
                                                        put("value", value)
                                                        put("name", value)
                                                        put("description", "Host description")
                                                    }
                                                    putJsonArray("options") {
                                                        for (id in
                                                            listOf(
                                                                "read-only",
                                                                "workspace-write",
                                                                "danger-full-access",
                                                                "auto",
                                                            )) add(option(id))
                                                    }
                                                    putJsonArray("defaultOptions") {
                                                        for (id in
                                                            listOf(
                                                                "read-only",
                                                                "workspace-write",
                                                                "danger-full-access",
                                                            )) add(option(id))
                                                    }
                                                    put("defaultPreset", "workspace-write")
                                                }
                                            "settings/describe" ->
                                                buildJsonObject {
                                                    put("writable", writable)
                                                    putJsonArray("namespaces") {
                                                        addJsonObject {
                                                            put("ns", "permission")
                                                            put("revision", revision.get())
                                                            put("writable", true)
                                                            putJsonObject("value") {
                                                                put(
                                                                    "defaultPreset",
                                                                    if (other) "read-only"
                                                                    else default.get(),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            "session/projections" -> {
                                                assertEquals(
                                                    "session",
                                                    args
                                                        .getValue("request")
                                                        .jsonObject
                                                        .getValue("sessionId")
                                                        .jsonPrimitive
                                                        .content,
                                                )
                                                if (failReadback) code = "read/unavailable"
                                                buildJsonObject {
                                                    put("asOfSeq", 100)
                                                    putJsonObject("values") {
                                                        putJsonObject("permissions") {
                                                            put("currentValue", current.get())
                                                        }
                                                    }
                                                }
                                            }
                                            "commands/execute" -> {
                                                commands.incrementAndGet()
                                                assertEquals(
                                                    "session",
                                                    args.getValue("agentId").jsonPrimitive.content,
                                                )
                                                assertEquals(
                                                    JsonArray(emptyList()),
                                                    args["submittedAttachments"],
                                                )
                                                val selected =
                                                    args
                                                        .getValue("line")
                                                        .jsonPrimitive
                                                        .content
                                                        .removePrefix("/permission ")
                                                if (holdWrite) {
                                                    entered.send(Unit)
                                                    proceed.receive()
                                                }
                                                if (!rejectCommand) current.set(selected)
                                                buildJsonObject {
                                                    put("commandId", "permission-command")
                                                    putJsonObject("result") {
                                                        put(
                                                            "kind",
                                                            if (rejectCommand) "error"
                                                            else "success",
                                                        )
                                                        put("text", "Host reply")
                                                    }
                                                }
                                            }
                                            "settings/update" -> {
                                                writes.incrementAndGet()
                                                assertEquals(
                                                    "permission",
                                                    args.getValue("ns").jsonPrimitive.content,
                                                )
                                                if (
                                                    args
                                                        .getValue("expectedRevision")
                                                        .jsonPrimitive
                                                        .int != revision.get()
                                                )
                                                    code = "settings/conflict"
                                                else {
                                                    if (holdWrite) {
                                                        entered.send(Unit)
                                                        proceed.receive()
                                                    }
                                                    default.set(
                                                        args
                                                            .getValue("patch")
                                                            .jsonObject
                                                            .getValue("defaultPreset")
                                                            .jsonPrimitive
                                                            .content,
                                                    )
                                                    revision.incrementAndGet()
                                                }
                                                JsonNull
                                            }
                                            else -> error("Unexpected permission request")
                                        }
                                    respond(
                                        buildJsonObject {
                                            put("id", body.getValue("id"))
                                            if (code == null) put("result", value)
                                            else putJsonObject("error") { put("code", code) }
                                        }
                                            .toString(),
                                        headers =
                                            headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                },
                        ),
                    )
                val one = client()
                val two = client(true)
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                val state = MutableStateFlow<PermissionsUiState?>(null)
                var consent = false
                var rememberCount = 0
                val controller =
                    PermissionsViewModel(
                        scope,
                        { "message:$it" },
                        { consent },
                        {
                            consent = true
                            rememberCount++
                        },
                    ) {
                        state.value = it
                    }
                suspend fun ready() =
                    state.filterNotNull().first { !it.loading && !it.busy && it.snapshot != null }
                try {
                    controller.connect(EnginePermissionsRepository(one))
                    controller.open("session")
                    controller.observe(true)
                    assertEquals("workspace-write", ready().snapshot!!.current)
                    controller.choose("danger-full-access", PermissionTarget.Current)
                    assertNotNull(state.value!!.pending)
                    assertEquals(0, commands.get())
                    controller.cancel()
                    assertNull(state.value!!.pending)
                    controller.choose("danger-full-access", PermissionTarget.Current)
                    holdWrite = true
                    controller.confirm()
                    entered.receive()
                    assertEquals("workspace-write", state.value!!.snapshot!!.current)
                    proceed.send(Unit)
                    assertEquals("danger-full-access", ready().snapshot!!.current)
                    holdWrite = false
                    controller.choose("auto", PermissionTarget.Current)
                    state.filterNotNull().first { it.pending?.value == "auto" }
                    controller.remember(false)
                    controller.confirm()
                    ready()
                    assertEquals("auto", state.value!!.snapshot!!.current)
                    assertEquals(0, rememberCount)
                    controller.choose("workspace-write", PermissionTarget.Current)
                    ready()
                    controller.choose("auto", PermissionTarget.Current)
                    state.filterNotNull().first { it.pending != null }
                    controller.confirm()
                    ready()
                    assertTrue(consent)
                    assertEquals(1, rememberCount)
                    controller.choose("workspace-write", PermissionTarget.Current)
                    ready()
                    controller.choose("auto", PermissionTarget.Current)
                    assertEquals("auto", ready().snapshot!!.current)
                    assertNull(state.value!!.pending)
                    val count = writes.get()
                    controller.choose("auto", PermissionTarget.Default)
                    assertEquals(count, writes.get())
                    controller.choose("danger-full-access", PermissionTarget.Default)
                    revision.incrementAndGet()
                    controller.confirm()
                    val conflict = ready()
                    assertTrue(conflict.stale)
                    assertEquals("settings/conflict", conflict.error)
                    assertEquals("workspace-write", default.get())
                    controller.refresh()
                    ready()
                    controller.choose("read-only", PermissionTarget.Default)
                    assertEquals("read-only", ready().snapshot!!.defaultValue)
                    assertEquals("auto", current.get())
                    rejectCommand = true
                    controller.choose("workspace-write", PermissionTarget.Current)
                    assertEquals("auto", ready().snapshot!!.current)
                    assertNotNull(state.value!!.error)
                    rejectCommand = false
                    failReadback = true
                    controller.choose("workspace-write", PermissionTarget.Current)
                    val unconfirmed = ready()
                    assertTrue(unconfirmed.stale)
                    assertNotNull(unconfirmed.notice)
                    assertEquals("workspace-write", current.get())
                    assertEquals("auto", unconfirmed.snapshot!!.current)
                    failReadback = false
                    controller.refresh()
                    assertEquals("workspace-write", ready().snapshot!!.current)
                    controller.choose("danger-full-access", PermissionTarget.Default)
                    controller.connect(EnginePermissionsRepository(two))
                    controller.open(null)
                    controller.observe(true)
                    ready()
                    controller.confirm()
                    assertEquals("read-only", state.value!!.snapshot!!.defaultValue)
                    assertNull(state.value!!.pending)
                    writable = false
                    controller.refresh()
                    assertFalse(ready().snapshot!!.defaults!!.writable)
                    val before = writes.get()
                    controller.choose("danger-full-access", PermissionTarget.Default)
                    assertEquals(before, writes.get())
                    controller.closeDialog()
                    assertNull(state.value)
                    controller.connect(null)
                    controller.open(null)
                    controller.observe(true)
                    assertFalse(state.value!!.loading)
                    assertTrue(state.value!!.stale)
                    assertNotNull(state.value!!.error)
                } finally {
                    controller.close()
                    scope.cancel()
                    one.close()
                    two.close()
                }
            }
        }
}

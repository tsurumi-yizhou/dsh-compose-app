package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelDefaultsApiTest {
    @Test
    fun fixedDefaultsUseOneRevisionedWriteAndDoNotChangeAnySession() = runBlocking {
        var revision = 17L
        var changes = 0
        var locked = false
        var inherited: String? = "high"
        var selection = ModelSelection("gateway", "old", "high")
        var remember = true
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()
            var conflict = false
            fun view() = buildJsonObject {
                put("ns", "agent-default-model")
                put("revision", revision)
                put(
                    "schema",
                    parseObject(
                        """{"uid":"root","refs":{
                    "root":{"type":"transform","inner":"object","meta":{"disabled":$locked}},
                    "object":{"type":"object","dict":{"provider":"str","model":"str","reasoningEffort":"optional","rememberLastSelection":"bool"}},
                    "str":{"type":"string"},"bool":{"type":"boolean"},
                    "optional":{"type":"union","list":["str","nothing"]},"nothing":{"type":"const","value":null}
                }}""",
                    ),
                )
                putJsonObject("value") {
                    put("provider", selection.provider)
                    put("model", selection.model)
                    selection.reasoningEffort?.let { put("reasoningEffort", it) }
                    put("rememberLastSelection", remember)
                }
                putJsonObject("base") { inherited?.let { put("reasoningEffort", it) } }
            }
            val value =
                when (input.string("method")) {
                    "settings/describe" ->
                        buildJsonObject {
                            put("writable", true)
                            put("namespaces", JsonArray(listOf(view())))
                        }
                    "settings/mutate" -> {
                        assertEquals("agent-default-model", args.string("ns"))
                        if (args.long("expectedRevision") != revision) {
                            conflict = true
                            JsonNull
                        } else {
                            val ops = args["ops"] as JsonArray
                            val selectedEffort = if (changes == 0) "low" else null
                            assertEquals(
                                JsonArray(
                                    listOf(
                                        parseObject(
                                            """{"op":"set","path":["provider"],"value":"gateway"}""",
                                        ),
                                        parseObject(
                                            """{"op":"set","path":["model"],"value":"new"}""",
                                        ),
                                        if (selectedEffort == null)
                                            parseObject(
                                                """{"op":"unset","path":["reasoningEffort"]}""",
                                            )
                                        else
                                            parseObject(
                                                """{"op":"set","path":["reasoningEffort"],"value":"low"}""",
                                            ),
                                        parseObject(
                                            """{"op":"set","path":["rememberLastSelection"],"value":false}""",
                                        ),
                                    ),
                                ),
                                ops,
                            )
                            changes++
                            revision++
                            selection = ModelSelection("gateway", "new", selectedEffort)
                            remember = false
                            view()
                        }
                    }
                    else -> error("Default settings must never mutate a session")
                }
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)
                    if (conflict) putJsonObject("error") { put("code", "settings/conflict") }
                    else put("result", value)
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val original = client.modelDefaults()!!
            assertTrue(original.writable)
            assertTrue(original.rememberLastSelection)
            val chosen = ModelSelection("gateway", "new", "low")
            client.setModelDefaults(original, chosen, false)
            var confirmed = client.modelDefaults()!!
            assertEquals(chosen, confirmed.selection)
            assertFalse(confirmed.rememberLastSelection)
            assertEquals(18L, confirmed.revision)
            try {
                client.setModelDefaults(original, chosen, false)
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("settings/conflict", error.code)
            }
            assertEquals(1, changes)
            inherited = null
            confirmed = client.modelDefaults()!!
            client.setModelDefaults(confirmed, chosen.copy(reasoningEffort = null), false)
            assertNull(client.modelDefaults()!!.selection!!.reasoningEffort)
            locked = true
            val readOnly = client.modelDefaults()!!
            assertFalse(readOnly.writable)
            assertEquals(2, changes)
        }
    }
}

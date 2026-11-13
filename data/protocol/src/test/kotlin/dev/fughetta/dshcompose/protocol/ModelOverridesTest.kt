package dev.fughetta.dshcompose.protocol

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelOverridesTest {
    @Test
    fun catalogContextEditsUseAtomicOverridePathsAndKeepOtherFields() = runBlocking {
        var revision = 11L
        var mutation = 0
        var overrides =
            parseObject(
                """{"model/a":{"name":"Custom","contextWindow":128000,"maxTokens":4096,"reasoningEfforts":{"high":16384},"vendorOption":true},"other":{"contextWindow":64000}}""",
            )
        fun view() = buildJsonObject {
            put("ns", "llm-pi-ai")
            put("revision", revision)
            putJsonObject("value") {
                putJsonObject("providers") {
                    putJsonObject("gateway") {
                        put("displayName", "Gateway")
                        put("apiKeyEnv", "GATEWAY_API_KEY")
                        if (overrides.isNotEmpty()) put("modelOverrides", overrides)
                    }
                }
            }
        }
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()
            val value =
                when (input.string("method")) {
                    "settings/describe" ->
                        buildJsonObject {
                            put("writable", true)
                            put("namespaces", JsonArray(listOf(view())))
                        }
                    "settings/mutate" -> {
                        assertEquals("llm-pi-ai", args.string("ns"))
                        assertEquals(revision, args.long("expectedRevision"))
                        val ops = args["ops"] as JsonArray
                        when (mutation++) {
                            0 -> {
                                assertEquals(2, ops.size)
                                val set = ops.first().objectValue()
                                assertEquals(
                                    parseObject(
                                        """{"op":"set","path":["providers","gateway","modelOverrides","model/a"],"value":{"name":"Custom","contextWindow":1500000,"maxTokens":4096,"reasoningEfforts":{"high":16384},"vendorOption":true}}""",
                                    ),
                                    set,
                                )
                                assertEquals(
                                    parseObject(
                                        """{"op":"unset","path":["providers","gateway","modelOverrides","other"]}""",
                                    ),
                                    ops.last(),
                                )
                                overrides = buildJsonObject { put("model/a", set["value"]!!) }
                            }
                            1 -> {
                                assertEquals(1, ops.size)
                                val set = ops.single().objectValue()
                                assertEquals(
                                    parseObject(
                                        """{"op":"set","path":["providers","gateway","modelOverrides","model/a"],"value":{"name":"Custom","maxTokens":4096,"reasoningEfforts":{"high":16384},"vendorOption":true}}""",
                                    ),
                                    set,
                                )
                                overrides = buildJsonObject { put("model/a", set["value"]!!) }
                            }
                            2 -> {
                                assertEquals(
                                    JsonArray(
                                        listOf(
                                            parseObject(
                                                """{"op":"unset","path":["providers","gateway","modelOverrides"]}""",
                                            ),
                                        ),
                                    ),
                                    ops,
                                )
                                overrides = JsonObject(emptyMap())
                            }
                            else -> error("Unexpected replay")
                        }
                        revision++
                        view()
                    }
                    else -> error("Unexpected request")
                }
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)

                    put("result", value)
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            var settings = client.providerSettings()!!
            val original = settings.find("gateway")!!
            assertFalse(original.handDeclared)
            val edited = original.modelOverrides.getValue("model/a").withContextWindow(1500000)
            settings =
                client.updateProvider(
                    original,
                    "Gateway",
                    original.credentialRef,
                    expectedRevision = settings.revision,
                    modelOverrides = mapOf("model/a" to edited),
                )
            assertEquals(
                1500000L,
                settings.find("gateway")!!.modelOverrides.getValue("model/a").contextWindow,
            )
            assertEquals(128000L, original.modelOverrides.getValue("model/a").contextWindow)
            var profile = settings.find("gateway")!!
            val cleared = profile.modelOverrides.getValue("model/a").withContextWindow(null)
            assertFalse(cleared.isEmpty)
            settings =
                client.updateProvider(
                    profile,
                    "Gateway",
                    profile.credentialRef,
                    expectedRevision = settings.revision,
                    modelOverrides = mapOf("model/a" to cleared),
                )
            profile = settings.find("gateway")!!
            assertNull(profile.modelOverrides.getValue("model/a").contextWindow)
            settings =
                client.updateProvider(
                    profile,
                    "Gateway",
                    profile.credentialRef,
                    expectedRevision = settings.revision,
                    modelOverrides = emptyMap(),
                )
            assertTrue(settings.find("gateway")!!.modelOverrides.isEmpty())
            assertEquals(3, mutation)
        }
    }
}

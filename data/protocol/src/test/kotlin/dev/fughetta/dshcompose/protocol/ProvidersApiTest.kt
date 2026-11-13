package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProvidersApiTest {
    @Test
    fun profileEditsUseAtomicPathsAndRevisionsAndKeepUneditedModelParameters() = runBlocking {
        var revision = 7L
        var changes = 0
        var calls = 0
        val model =
            parseObject(
                """{"id":"model1","name":"Model One","maxTokens":8192,"reasoningEfforts":{"off":null,"high":"high"},"vendorOption":{"enabled":true}}""",
            )
        var profiles = buildJsonObject {
            putJsonObject("gateway") {
                put("displayName", "Original")
                put("api", "openai-completions")
                put("baseURL", "https://example.test/v1/")
                put("apiKeyEnv", "GATEWAY_API_KEY")
                put("models", JsonArray(listOf(model)))
                putJsonObject("headers") { put("Authorization", "synthetic-private-header") }
            }
        }
        fun view() = buildJsonObject {
            put("ns", "llm-pi-ai")
            put("revision", revision)
            putJsonObject("value") { put("providers", profiles) }
            putJsonObject("user") { put("providers", profiles) }
            putJsonObject("base") { putJsonObject("providers") {} }
        }
        val engine = MockEngine { request ->
            calls++
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()
            var conflict = false
            val value =
                when (input.string("method")) {
                    "settings/describe" ->
                        buildJsonObject {
                            put("writable", true)
                            put("namespaces", JsonArray(listOf(view())))
                        }
                    "settings/mutate" -> {
                        assertEquals("llm-pi-ai", args.string("ns"))
                        if (args.long("expectedRevision") != revision) {
                            conflict = true
                            JsonNull
                        } else {
                            val ops = args["ops"] as JsonArray
                            assertEquals(1, ops.size)
                            val op = ops.single().objectValue()
                            when (changes++) {
                                0 -> {
                                    assertEquals(
                                        parseObject(
                                            """{"op":"set","path":["providers","gateway","displayName"],"value":"Updated"}""",
                                        ),
                                        op,
                                    )
                                    profiles = buildJsonObject {
                                        profiles.forEach { (id, value) ->
                                            put(
                                                id,
                                                if (id == "gateway")
                                                    buildJsonObject {
                                                        value.objectValue().forEach { (key, data) ->
                                                            put(key, data)
                                                        }
                                                        put("displayName", "Updated")
                                                    }
                                                else value,
                                            )
                                        }
                                    }
                                }
                                1 -> {
                                    assertEquals(
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("providers"),
                                                JsonPrimitive("new-route"),
                                            ),
                                        ),
                                        op["path"],
                                    )
                                    val profile = op["value"]!!.objectValue()
                                    assertEquals("anthropic-messages", profile.string("api"))
                                    assertEquals("NEW_ROUTE_API_KEY", profile.string("apiKeyEnv"))
                                    profiles = buildJsonObject {
                                        profiles.forEach { (id, data) -> put(id, data) }
                                        put("new-route", profile)
                                    }
                                }
                                2 -> {
                                    assertEquals(
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("providers"),
                                                JsonPrimitive("gateway"),
                                                JsonPrimitive("models"),
                                            ),
                                        ),
                                        op["path"],
                                    )
                                    val models = op["value"] as JsonArray
                                    assertEquals(model, models.first())
                                    assertEquals(
                                        parseObject("""{"id":"second/model"}"""),
                                        models.last(),
                                    )
                                    profiles = buildJsonObject {
                                        profiles.forEach { (id, value) ->
                                            put(
                                                id,
                                                if (id == "gateway")
                                                    buildJsonObject {
                                                        value.objectValue().forEach { (key, data) ->
                                                            put(key, data)
                                                        }
                                                        put("models", models)
                                                    }
                                                else value,
                                            )
                                        }
                                    }
                                }
                                3 -> {
                                    assertEquals(
                                        JsonArray(
                                            listOf(
                                                JsonPrimitive("providers"),
                                                JsonPrimitive("gateway"),
                                                JsonPrimitive("models"),
                                            ),
                                        ),
                                        op["path"],
                                    )
                                    val models = op["value"] as JsonArray
                                    val edited = models.first().objectValue()
                                    assertEquals("model1", edited.string("id"))
                                    assertEquals(1500000L, edited.long("contextWindow"))
                                    assertEquals(
                                        model["reasoningEfforts"],
                                        edited["reasoningEfforts"],
                                    )
                                    assertEquals(model["vendorOption"], edited["vendorOption"])
                                    profiles = buildJsonObject {
                                        profiles.forEach { (id, value) ->
                                            put(
                                                id,
                                                if (id == "gateway")
                                                    buildJsonObject {
                                                        value.objectValue().forEach { (key, data) ->
                                                            put(key, data)
                                                        }
                                                        put("models", models)
                                                    }
                                                else value,
                                            )
                                        }
                                    }
                                }
                                4 -> {
                                    assertEquals(
                                        parseObject(
                                            """{"op":"unset","path":["providers","new-route"]}""",
                                        ),
                                        op,
                                    )
                                    profiles = JsonObject(profiles.filterKeys { it != "new-route" })
                                }
                                else -> error("Mutation was replayed")
                            }
                            revision++
                            view()
                        }
                    }
                    else -> error("Unexpected credential modification or request")
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
            var settings = client.providerSettings()!!
            val original = settings.find("gateway")!!
            assertTrue(original.handDeclared)
            assertFalse(original.toString().contains("synthetic-private-header"))
            settings =
                client.updateProvider(
                    original,
                    "Updated",
                    original.credentialRef,
                    expectedRevision = settings.revision,
                )
            assertEquals(8L, settings.revision)
            try {
                client.updateProvider(
                    original,
                    "Stale",
                    original.credentialRef,
                    expectedRevision = 7,
                )
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("settings/conflict", error.code)
            }
            settings =
                client.createProvider(
                    "new-route",
                    "New",
                    ProviderApi.AnthropicMessages,
                    normalizeProviderBaseUrl(
                        "https://example.test/v1/",
                        ProviderApi.AnthropicMessages,
                    ),
                    deriveProviderKeyRef("new-route"),
                    listOf(ProviderModelSpec.create("model2")),
                    settings.revision,
                )
            assertEquals("https://example.test", settings.find("new-route")!!.baseUrl)
            val retained = settings.find("gateway")!!
            settings =
                client.updateProvider(
                    retained,
                    retained.displayName!!,
                    retained.credentialRef,
                    models = retained.models + ProviderModelSpec.create("second/model"),
                    expectedRevision = settings.revision,
                )
            assertEquals("https://example.test/v1/", settings.find("gateway")!!.baseUrl)
            val parameterProfile = settings.find("gateway")!!
            settings =
                client.updateProvider(
                    parameterProfile,
                    parameterProfile.displayName!!,
                    parameterProfile.credentialRef,
                    models =
                        parameterProfile.models.map {
                            if (it.id == "model1") it.withParameters(it.name, 1500000, it.maxTokens)
                            else it
                        },
                    expectedRevision = settings.revision,
                )
            assertEquals(1500000L, settings.find("gateway")!!.models.first().contextWindow)
            assertTrue(settings.removable("new-route"))
            settings = client.removeProvider("new-route", settings.revision)
            assertNull(settings.find("new-route"))
            assertEquals(5, changes)
            assertEquals(7, calls)
        }
    }
}

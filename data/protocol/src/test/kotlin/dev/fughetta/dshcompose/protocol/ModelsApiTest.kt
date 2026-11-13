package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelsApiTest {
    @Test
    fun catalogEffortsAndSelectedModelRemainSeparateFromSavingTheDefault() = runBlocking {
        var selections = 0
        var defaultEffort = "high"
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val value =
                when (input.string("method")) {
                    "session/modelCatalog" ->
                        parseObject(
                            """{
                    "default":{"provider":"account","model":"model1","reasoningEffort":"$defaultEffort"},
                    "routableProviders":["account"],
                    "groups":[{"id":"account","name":"My provider","models":[{"id":"model1","name":"Model One",
                        "reasoning":{"defaultEffort":"high","efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"}]}},
                        {"id":"model2","name":"Custom","reasoning":{"efforts":[{"id":"off"},{"id":"low"},{"id":"medium"}]}}]}],
                    "failures":[{"id":"broken","name":"Other provider","message":"synthetic-private-diagnostic"}]
                }""",
                        )
                    "session/selectModel" -> {
                        selections++
                        val args = input["args"]!!.objectValue()["request"]!!.objectValue()
                        assertEquals(
                            parseObject(
                                """{"sessionId":"s1","provider":"account","model":"model1","reasoningEffort":"high"}""",
                            ),
                            args,
                        )
                        if (selections == 2) defaultEffort = "off"
                        parseObject(
                            """{
                        "selected":{"provider":"account","model":"model1","reasoningEffort":"off"},
                        "defaultSave":{"status":"${if (selections == 1) "failed" else "saved"}"}
                    }""",
                        )
                    }
                    else -> error("Unexpected model request")
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
            val catalog = client.listModels()
            val wanted = ModelSelection("account", "model1", "high")
            assertEquals(listOf("off", "high"), catalog.find(wanted)!!.efforts.map { it.id })
            assertEquals("high", catalog.find(wanted)!!.initialEffort)
            assertEquals("medium", catalog.find(wanted.copy(model = "model2"))!!.initialEffort)
            assertTrue(catalog.accepts(wanted))
            assertFalse(catalog.accepts(wanted.copy(reasoningEffort = "unlisted-effort")))
            assertEquals("My provider", catalog.groups.single().name)
            assertFalse(catalog.toString().contains("synthetic-private-diagnostic"))
            val accepted = client.selectModel("s1", wanted)
            assertEquals("off", accepted.selected.reasoningEffort)
            assertEquals(ModelDefaultSaveStatus.Failed, accepted.defaultSave)
            assertEquals("high", client.listModels().defaultSelection!!.reasoningEffort)
            assertEquals(ModelDefaultSaveStatus.Saved, client.selectModel("s1", wanted).defaultSave)
            assertEquals("off", client.listModels().defaultSelection!!.reasoningEffort)
        }
    }
}

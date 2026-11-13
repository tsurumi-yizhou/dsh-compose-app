package dev.fughetta.dshcompose.data.media

import dev.fughetta.dshcompose.domain.media.MediaCatalogStatus
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(
    sdk = [28],
    application = android.app.Application::class,
    manifest = org.robolectric.annotation.Config.NONE,
)
class EngineMediaRepositoryTest {
    @Test
    fun catalogSchemaAndCredentialsProduceSelectableMediaEntries() = runBlocking {
        val settings =
            Json.parseToJsonElement(
                """{
                    "namespaces": [{
                        "ns": "media-generation", "revision": 3,
                        "schema": {"uid": 0, "refs": {
                            "0": {"type": "object", "dict": {"services": 1}},
                            "1": {"type": "transform", "inner": 2},
                            "2": {"type": "array", "meta": {"extra": {
                                "dsh-compose/media/catalog": {
                                    "v": 1, "contract": "dsh-compose/media/catalog",
                                    "models": [{
                                        "id": "image-basic", "label": "Image Basic",
                                        "kind": "image", "profile": "images",
                                        "price": {"amount": 0.025, "unit": "image", "currency": "USD"}
                                    }]
                                }
                            }}}
                        }},
                        "value": {"services": [{
                            "id": "image", "label": "Images", "enabled": true,
                            "provider": "example", "profile": "images",
                            "baseUrl": "https://images.example.test",
                            "models": ["image-basic"], "keyRef": "MEDIA_KEY"
                        }]}
                    }]
                }""",
            )
        val engine = MockEngine { request ->
            val input = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val value =
                when (input["method"]!!.jsonPrimitive.content) {
                    "settings/describe" -> settings
                    "credentials/describe" -> {
                        val args = input["args"]!!.jsonObject
                        assertEquals(JsonArray(listOf(JsonPrimitive("MEDIA_KEY"))), args["refs"])
                        Json.parseToJsonElement(
                            """{"MEDIA_KEY":{"configured":true,"writable":true}}""",
                        )
                    }
                    else -> error("Unexpected catalog request")
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
        ProtocolClient(HostConnection("https://host.test", engine = engine)).use { client ->
            val repository =
                EngineMediaRepository(org.robolectric.RuntimeEnvironment.getApplication())
            repository.connect(client)
            val catalog = repository.readMediaCatalog()
            assertEquals(MediaCatalogStatus.Ready, catalog.status)
            val entry = catalog.entries.single()
            assertEquals("image", entry.service.id)
            assertEquals("image-basic", entry.model.id)
            assertEquals(0.025, entry.model.price!!.amount!!, 0.0)
            assertEquals(3L, catalog.namespaces.single().revision)
        }
    }
}

package dev.fughetta.dshcompose.protocol

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineSettingsFlowTest {
    @Test
    fun configWritesKeepOpaqueRevisionAndCredentialsPrivate() = runBlocking {
        var reads = 0
        var writes = 0
        val schema =
            """{"uid":0,"refs":{"0":{"type":"object","dict":{"enabled":1,"apiKey":2}},"1":{"type":"boolean"},"2":{"type":"string","meta":{"role":"secret"}}}}"""
        fun detail(revision: String, enabled: Boolean) =
            """{"entryId":"example","revision":"$revision","schema":$schema,"value":{"enabled":$enabled},"secrets":[{"path":["apiKey"],"configured":true,"writable":false}],"writable":true}"""
        val engine = MockEngine { request ->
            assertEquals(
                "Bearer private-memory-value",
                request.headers[HttpHeaders.Authorization],
            )
            assertNull(request.headers[HttpHeaders.Cookie])
            when (request.url.encodedPath) {
                "/compose/extensions/v1/plugin-config/example" -> {
                    val result =
                        if (request.method == HttpMethod.Post) {
                            writes++
                            val body =
                                Json.parseToJsonElement((request.body as TextContent).text)
                                    .jsonObject
                            assertEquals(
                                "opaque-before",
                                body["revision"]!!.jsonPrimitive.content,
                            )
                            assertEquals(
                                Json.parseToJsonElement(
                                    """[{"op":"set","path":["enabled"],"value":false}]""",
                                ),
                                body["ops"],
                            )
                            detail("opaque-after", false)
                        } else {
                            reads++
                            detail("opaque-before", true)
                        }
                    respond(
                        result,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected route")
            }
        }
        ProtocolClient(
                HostConnection(
                    "https://host.test",
                    credential = HostCredential("private-memory-value", "host.test"),
                    engine = engine,
                ),
            )
            .use { client ->
                val initial = client.readPluginConfig("example")
                assertTrue(initial.schema.containsSecret())
                assertTrue(initial.schema.fields()!!.getValue("apiKey").secret)
                assertNull(initial.value["apiKey"])
                val updated =
                    client.writePluginConfig(
                        initial,
                        listOf(SettingsOp(listOf("enabled"), JsonPrimitive(false))),
                    )
                assertEquals("opaque-after", updated.revision)
                assertEquals(false, updated.value["enabled"]!!.jsonPrimitive.boolean)
                assertEquals(1, reads)
                assertEquals(1, writes)
            }
    }
}

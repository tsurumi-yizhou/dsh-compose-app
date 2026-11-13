package dev.fughetta.dshcompose.data.plugins

import dev.fughetta.dshcompose.domain.plugins.PluginApplication
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EnginePluginsRepositoryTest {
    @Test
    fun backgroundPluginLifecycleRunsIndependentlyOfClientSurfaces() = runBlocking {
        var installed = false
        var enabled = false
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            val input = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = input.getValue("method").jsonPrimitive.content
            val args = input.getValue("args").jsonObject
            calls += method
            val result =
                when (method) {
                    "pluginManager/inspect" -> {
                        assertEquals(
                            "background-plugin",
                            args.getValue("spec").jsonPrimitive.content,
                        )
                        buildJsonObject {
                            put("name", "background-plugin")
                            put("bundle", true)
                        }
                    }
                    "pluginManager/installBundle" -> {
                        assertFalse(installed)
                        val options = args.getValue("options").jsonObject
                        assertEquals(
                            "install-1",
                            options.getValue("requestId").jsonPrimitive.content,
                        )
                        assertTrue(options.getValue("enabled").jsonPrimitive.boolean)
                        installed = true
                        enabled = true
                        applied()
                    }
                    "pluginManager/listBundles" ->
                        JsonArray(
                            if (!installed) emptyList()
                            else
                                listOf(
                                    buildJsonObject {
                                        put("name", "background-plugin")
                                        put("installed", true)
                                        put("enabled", enabled)
                                    },
                                ),
                        )
                    "pluginManager/listPlugins" ->
                        JsonArray(
                            if (!installed) emptyList()
                            else
                                listOf(
                                    buildJsonObject {
                                        put("entryId", "background")
                                        put("moduleName", "background-plugin")
                                        put("enabled", enabled)
                                        put("fiberPhase", if (enabled) "active" else "disposed")
                                    },
                                ),
                        )
                    "pluginManager/setPluginEnabled" -> {
                        assertEquals("background", args.getValue("id").jsonPrimitive.content)
                        enabled = args.getValue("enabled").jsonPrimitive.boolean
                        applied()
                    }
                    "pluginManager/removeBundle" -> {
                        assertEquals(
                            "background-plugin",
                            args.getValue("name").jsonPrimitive.content,
                        )
                        installed = false
                        enabled = false
                        applied()
                    }
                    else ->
                        error("Background plugin lifecycle requested an unrelated service: $method")
                }
            respond(
                buildJsonObject {
                    put("id", input.getValue("id"))
                    put("result", result)
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("https://host", engine = engine)).use { client ->
            val ui = EnginePluginUiRepository(client)
            ui.close()
            val host = EnginePluginsRepository(client)
            assertTrue(host.inspect("background-plugin").accepted)
            assertEquals(
                PluginApplication.Applied,
                host.install("background-plugin", "install-1", null).application,
            )
            assertEquals("background-plugin", host.bundles().single().name)
            assertTrue(host.plugins().single().enabled)
            host.setPluginEnabled("background", false)
            assertFalse(host.plugins().single().enabled)
            host.setPluginEnabled("background", true)
            assertEquals("active", host.plugins().single().fiberPhase)
            host.removeBundle("background-plugin")
            assertTrue(host.bundles().isEmpty())
            val completed = calls.size
            host.close()
            try {
                host.setPluginEnabled("background", true)
                fail("Closed host must reject operations")
            } catch (_: ConnectionClosedException) {
                assertEquals(completed, calls.size)
            }
        }
    }

    private fun applied() = buildJsonObject {
        put("application", "applied")
        put("changed", true)
    }
}

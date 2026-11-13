package dev.fughetta.dshcompose.data.plugins

import dev.fughetta.dshcompose.domain.plugins.*
import java.lang.reflect.Proxy
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PluginCatalogTest {
    @Test
    fun hostPluginsRemainManageableWhenSupplementalReadsFailAndRecoverOnRefresh() = runTest {
        val entries =
            persistentListOf(
                PluginEntry("background", "background-plugin", true, "active", null, null, null),
            )
        var unavailable = true
        val calls = mutableListOf<String>()
        val repository =
            Proxy.newProxyInstance(
                PluginsRepository::class.java.classLoader,
                arrayOf(PluginsRepository::class.java),
            ) { _, method, _ ->
                calls += method.name
                when (method.name) {
                    "bundles" -> emptyList<PluginBundle>()
                    "plugins" -> entries
                    "getCanReadCordis",
                    "getCanReadCompletePluginConfig" -> true
                    "cordis" -> {
                        if (unavailable) error("Runner unavailable")
                        persistentListOf<CordisPlugin>()
                    }
                    "config" -> {
                        if (unavailable) error("Configuration unavailable")
                        persistentListOf(
                            PluginConfigEntrySummary(
                                "background",
                                "background-plugin",
                                null,
                                "active",
                                true,
                                null,
                                "installed",
                                "background-plugin",
                                "plugin",
                                null,
                                true,
                            ),
                        )
                    }
                    else -> error("Host inventory must not request client UI: ${method.name}")
                }
            } as PluginsRepository
        PluginCatalog().use { catalog ->
            val partial = catalog.inventory(repository)
            assertEquals(entries, partial.entries)
            assertTrue(partial.cordisUnavailable)
            assertTrue(partial.configUnavailable)
            unavailable = false
            val recovered = catalog.inventory(repository)
            assertEquals(entries, recovered.entries)
            assertEquals(setOf("background"), recovered.configurable)
            assertFalse(recovered.cordisUnavailable)
            assertFalse(recovered.configUnavailable)
            assertEquals(2, calls.count { it == "plugins" })
        }
    }

    @Test
    fun cancellingInventoryDoesNotPublishPartialSuccess() = runTest {
        val repository =
            Proxy.newProxyInstance(
                PluginsRepository::class.java.classLoader,
                arrayOf(PluginsRepository::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "bundles" -> emptyList<PluginBundle>()
                    "plugins" -> persistentListOf<PluginEntry>()
                    "getCanReadCordis" -> true
                    "getCanReadCompletePluginConfig" -> false
                    "cordis" -> throw CancellationException("Host changed")
                    else -> error(method.name)
                }
            } as PluginsRepository
        PluginCatalog().use { catalog ->
            try {
                catalog.inventory(repository)
                fail("Cancelled inventory must be discarded")
            } catch (_: CancellationException) {
                // The caller retains ownership of host switching and cancellation.
            }
        }
    }
}

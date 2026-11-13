package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.features.HostModule
import dev.fughetta.dshcompose.domain.features.ModuleRegistry
import dev.fughetta.dshcompose.domain.nativeplugins.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppModulesTest {
    @Test
    fun dependencyRemovalWithdrawsExtensionEntriesWhileShellCommandsRemainUsable() = runBlocking {
        val registry = ModuleRegistry<AppModuleContribution>(this)
        val pkg = "@test/notes"
        val action = NativePluginAction(pkg, "add", "Add", "composer.actions")
        val plugin =
            NativePlugin(
                pkg,
                "Notes",
                emptySet(),
                emptySet(),
                listOf(action),
                listOf(
                    NativePluginPanel(pkg, "notes", "Notes", listOf("features"), false, true, null),
                ),
                emptyList(),
                listOf("summary"),
                emptySet(),
                emptyList(),
                ToolPayload.Obj(emptyMap()),
                setOf("@test/base"),
            )
        val extension =
            HostModule(
                pluginModuleId(pkg),
                plugin.dependencies.map(::pluginModuleId).toSet(),
                nativeModuleContributions(plugin),
            )
        val base =
            HostModule<AppModuleContribution>(
                pluginModuleId("@test/base"),
                contributions = emptyList(),
            )
        try {
            registry.publish("builtin", listOf(builtInModule()))
            val shell = registry.state.value.contributions
            registry.publish("compose", listOf(extension))
            assertEquals(shell, registry.state.value.contributions)
            registry.publish("compose", listOf(base, extension))
            val button =
                registry.state.value.contributions
                    .filterIsInstance<AppModuleContribution.ComposerAction>()
                    .single { it.plugin == pkg }
            val dispatch =
                button.invoke(ComposerActionContext("session", 0, 0, false, false, false))
                    as AppAction.NativePluginInvoke
            assertEquals(action, dispatch.action)
            assertEquals(NativePluginScope("session"), dispatch.scope)
            val pages =
                registry.state.value.contributions.filterIsInstance<AppModuleContribution.Page>()
            assertTrue(pages.any { (it.target as? PageTarget.Extension)?.panel?.pkg == pkg })
            registry.publish("compose", listOf(extension))
            assertEquals(shell, registry.state.value.contributions)
            val command =
                registry.state.value.contributions
                    .filterIsInstance<AppModuleContribution.Command>()
                    .single { it.name == "new" }
            assertEquals(AppAction.NewSession, command.action("session"))
        } finally {
            registry.close()
        }
    }
}

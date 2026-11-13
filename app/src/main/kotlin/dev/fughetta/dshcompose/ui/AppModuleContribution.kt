package dev.fughetta.dshcompose.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import dev.fughetta.dshcompose.domain.commands.SlashCommand
import dev.fughetta.dshcompose.domain.commands.SlashCommandKind
import dev.fughetta.dshcompose.domain.nativeplugins.NativePlugin
import dev.fughetta.dshcompose.domain.nativeplugins.NativePluginPanel
import dev.fughetta.dshcompose.ui.shell.FeatureEntry

/** Entries published by built-in modules and by the selected compose service. */
sealed interface AppModuleContribution {
    data class Page(val ids: List<String>, val menu: FeaturePage?, val target: PageTarget) :
        AppModuleContribution

    data class Command(
        val name: String,
        val label: Int,
        val description: Int,
        val action: (String) -> AppAction,
    ) : AppModuleContribution {
        fun describe(text: (Int) -> String) =
            SlashCommand(
                name,
                text(label),
                text(description),
                null,
                SlashCommandKind.App,
            )
    }

    data class ComposerAction(
        val id: String,
        val surface: String,
        val label: (Context) -> String,
        val invoke: (ComposerActionContext) -> AppAction,
        val enabled: (ComposerActionContext) -> Boolean = { !it.readOnly && !it.sending },
        val icon: Int? = null,
        val plugin: String? = null,
        val busyKey: String? = null,
    ) : AppModuleContribution

    data class MessageRenderer(
        val id: String,
        val accepts: (MessageRenderContext) -> Boolean,
        val render: @Composable (MessageRenderContext) -> Unit,
    ) : AppModuleContribution

    data class SearchEntry(val entry: FeatureEntry) : AppModuleContribution

    data class Menu(val entry: FeatureContribution) : AppModuleContribution

    data class Extension(val plugin: NativePlugin) : AppModuleContribution
}

internal fun pluginModuleId(pkg: String) = "plugin:$pkg"

data class ComposerActionContext(
    val sessionId: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val readOnly: Boolean,
    val sending: Boolean,
    val syncing: Boolean,
)

val LocalComposerActions = staticCompositionLocalOf { builtInComposerActions }

sealed interface PageTarget {
    data class Navigation(val destination: () -> NavKey) : PageTarget

    data class Extension(val panel: NativePluginPanel) : PageTarget
}

val LocalFeaturePages = staticCompositionLocalOf { builtInPages }

internal fun nativeModuleContributions(plugin: NativePlugin): List<AppModuleContribution> =
    listOf(AppModuleContribution.Extension(plugin)) +
        plugin.panels.map { panel ->
            AppModuleContribution.Page(
                listOf("plugin:${plugin.pkg}:${panel.id}"),
                null,
                dev.fughetta.dshcompose.ui.PageTarget.Extension(panel),
            )
        } +
        plugin.actions
            .filter { it.surface in setOf("composer.actions", "composer.tools", "session.actions") }
            .map { action ->
                AppModuleContribution.ComposerAction(
                    id = "plugin:${plugin.pkg}:${action.surface}:${action.id}",
                    surface = action.surface,
                    label = { action.label },
                    plugin = plugin.pkg,
                    busyKey = "${plugin.pkg}|${action.surface}|${action.id}",
                    invoke = { context ->
                        dev.fughetta.dshcompose.ui.AppAction.NativePluginInvoke(
                            action,
                            dev.fughetta.dshcompose.domain.nativeplugins.NativePluginScope(
                                context.sessionId,
                            ),
                        )
                    },
                )
            } +
        if (plugin.turnNotes.isNotEmpty())
            listOf(dev.fughetta.dshcompose.ui.nativeTurnNoteRenderer(plugin))
        else emptyList()

package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.nativeplugins.NativePlugin

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

package dev.fughetta.dshcompose.engine_settings

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleEnginePlugins(action: AppAction): Boolean {
    when (action) {
        is AppAction.EngineControls -> {
            engineControls.action(action.action)
            return true
        }
        is AppAction.OpenPluginSeats -> {
            mutableState.update {
                it.copy(
                    pluginSeatSession = action.sessionId,
                    openPluginSeatsRevision = it.openPluginSeatsRevision + 1,
                )
            }
            return true
        }
        is AppAction.PluginSeats -> {
            pluginSeats.action(action.action)
            return true
        }
        is AppAction.EngineConfiguration -> {
            engineSettings.action(action.action)
            return true
        }
        is AppAction.PluginManagement -> {
            pluginManagement.action(action.action)
            return true
        }
        is AppAction.ConfigurePlugin -> {

            engineSettings.action(
                dev.fughetta.dshcompose.ui.engine_settings.EngineSettingsAction.OpenPlugin(
                    action.entryId,
                ),
            )
            mutableState.update {
                it.copy(openEngineSettingsRevision = it.openEngineSettingsRevision + 1)
            }

            return true
        }
        AppAction.OpenSchedules -> {
            mutableState.update { it.copy(openSchedulesRevision = it.openSchedulesRevision + 1) }
            return true
        }
        AppAction.InstallRuntime -> {
            engineControls.install()
            return true
        }
        AppAction.StartRuntime -> {
            engineControls.action(
                dev.fughetta.dshcompose.ui.runtime.EngineControlsAction.Start,
            )
            return true
        }
        AppAction.StopRuntime -> {
            engineControls.action(dev.fughetta.dshcompose.ui.runtime.EngineControlsAction.Stop)
            return true
        }
        else -> return false
    }
}

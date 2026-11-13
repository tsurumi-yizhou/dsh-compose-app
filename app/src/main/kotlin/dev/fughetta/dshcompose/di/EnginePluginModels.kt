package dev.fughetta.dshcompose.di

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.restartLocalRuntime
import dev.fughetta.dshcompose.runtime.AppRuntime
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createRuntime() = run { AppRuntime.get(app) }

internal fun AppViewModel.createEngineSettings() = run {
    ownedModel { dev.fughetta.dshcompose.engine_settings.EngineSettingsViewModel() }
}

internal fun AppViewModel.createPluginManagement() = run {
    ownedModel {
        dev.fughetta.dshcompose.engine_settings.PluginManagementViewModel(
            null,
            market = dev.fughetta.dshcompose.data.plugins.EnginePluginsRepository(),
            onRestartRequested = {
                check(selectedHost == "local") { "Remote engine restart is unavailable" }
                // The expected Host disconnect must not cancel the already confirmed
                // physical
                // restart.
                viewModelScope.async { restartLocalRuntime() }.await()
            },
        )
    }
}

internal fun AppViewModel.createEngineControls() = run {
    ownedModel {
        dev.fughetta.dshcompose.engineui.EngineControlsViewModel(
            app,
            null,
            phoneStorage = { phoneControls.phoneStorageEnabled },
            hasModelAccess = {
                localObserver.lease?.generation?.let(deepSeekAccount::identityFor) == true ||
                    selectedHost == "local" &&
                        (app as ClientApplication)
                            .providersRepository
                            .value
                            ?.snapshot
                            ?.value
                            ?.credentials
                            ?.values
                            ?.any { it.configured } == true
            },
        )
    }
}

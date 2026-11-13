package dev.fughetta.dshcompose.di

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createAppearanceController() = run {
    dev.fughetta.dshcompose.appearance.AppearanceController(
        viewModelScope,
        app,
        icons,
    )
}

internal fun AppViewModel.createUiConfig() = run {
    ownedModel {
        dev.fughetta.dshcompose.uiconfig.UiConfigViewModel(
            app,
            null,
            settings = { maxBytes ->
                dev.fughetta.dshcompose.data.settings.createSettingsRepository(
                    app,
                    viewModelScope,
                    uiConfig =
                        dev.fughetta.dshcompose.data.uiconfig.UiConfigDataSource(
                            app,
                            rootfs = {
                                java.io.File(app.filesDir, "runtime/rootfs").takeIf {
                                    it.isDirectory
                                }
                            },
                            mayWrite = {
                                mutableState.value.runtime.phase != RuntimePhase.Installing
                            },
                            foreground = { foreground },
                            maxBytes = maxBytes,
                        ),
                )
            },
        ) { config ->
            val mode =
                when (config?.themeMode) {
                    "system" -> ThemeMode.System
                    "light" -> ThemeMode.Light
                    "dark" -> ThemeMode.Dark
                    else -> null
                }
            mutableState.update { it.copy(uiThemeMode = mode) }
        }
    }
}

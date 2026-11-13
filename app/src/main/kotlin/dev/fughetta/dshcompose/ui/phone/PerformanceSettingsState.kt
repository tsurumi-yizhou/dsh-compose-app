package dev.fughetta.dshcompose.ui.phone

import androidx.compose.runtime.staticCompositionLocalOf

data class PerformanceSettingsState(
    val mode: String = "auto",
    val refreshRate: String = "auto",
    val animationsOverride: Boolean? = null,
    val fluidOverride: Boolean? = null,
    val thermalLightweight: Boolean = false,
    val lightweight: Boolean = false,
    val animations: Boolean = true,
    val fluid: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val autoStartOverride: Boolean? = null,
    val autoStartLocal: Boolean = true,
)

sealed interface PerformanceSettingsAction {
    data class Mode(val value: String) : PerformanceSettingsAction

    data class RefreshRate(val value: String) : PerformanceSettingsAction

    data class Animations(val value: Boolean) : PerformanceSettingsAction

    data class Fluid(val value: Boolean) : PerformanceSettingsAction

    data class AutoStartLocal(val value: Boolean?) : PerformanceSettingsAction

    data object ResetEffects : PerformanceSettingsAction
}

val LocalPerformanceSettings = staticCompositionLocalOf { PerformanceSettingsState() }

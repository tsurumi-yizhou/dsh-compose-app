package dev.fughetta.dshcompose.phoneui

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handlePhoneControls(action: AppAction): Boolean {
    when (action) {
        is AppAction.BackgroundNet -> {

            backgroundNet.dismiss()
            if (action.open) openFeature("phone")

            return true
        }
        is AppAction.PerformanceSettings -> {
            performanceSettings.action(action.action)
            return true
        }
        is AppAction.PhoneControls -> {
            phoneControls.action(action.action)
            return true
        }
        else -> return false
    }
}

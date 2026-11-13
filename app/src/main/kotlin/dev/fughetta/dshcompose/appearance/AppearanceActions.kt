package dev.fughetta.dshcompose.appearance

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleAppearance(action: AppAction): Boolean {
    when (action) {
        is AppAction.Appearance -> {
            appearanceController.action(action.action)
            return true
        }
        AppAction.OpenAppearance -> {
            mutableState.update { it.copy(openAppearanceRevision = it.openAppearanceRevision + 1) }
            return true
        }
        AppAction.FinishAppearanceLaunch -> {
            mutableState.update { it.copy(appearanceLaunchFinished = true) }
            return true
        }
        else -> return false
    }
}

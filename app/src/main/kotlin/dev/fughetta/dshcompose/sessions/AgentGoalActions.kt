package dev.fughetta.dshcompose.sessions

import dev.fughetta.dshcompose.AppViewModel
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.flow.update

internal fun AppViewModel.handleAgentsGoals(action: AppAction): Boolean {
    when (action) {
        is AppAction.OpenAgents -> {
            mutableState.update { it.copy(openAgentsRevision = it.openAgentsRevision + 1) }
            return true
        }
        is AppAction.SelectAgentPreset -> {
            selectAgentPreset(action.sessionId, action.preset)
            return true
        }
        else -> return false
    }
}

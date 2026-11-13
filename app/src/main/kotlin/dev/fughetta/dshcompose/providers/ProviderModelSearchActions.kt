package dev.fughetta.dshcompose.providers

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.flow.*

internal fun AppViewModel.handleProvidersModelsSearch(action: AppAction): Boolean {
    when (action) {
        AppAction.OpenProviderSettings -> {
            openFeature("providers")
            return true
        }
        is AppAction.OpenSearchSession -> {
            if (
                client != null &&
                    state.value.workspaces.connected &&
                    !state.value.workspaces.loading &&
                    !state.value.workspaces.writing &&
                    state.value.workspaces.error == null &&
                    sessionRepository.get(action.sessionId) != null
            ) {
                if (action.sessionId in state.value.workspaces.archived)
                    workspaces.manageSession(
                        action.sessionId,
                        WorkspaceSessionOperation.Restore,
                        openAfterRestore = true,
                    )
                else {
                    mutableState.update {
                        it.copy(
                            openSessionId = action.sessionId,
                            openSessionRevision = it.openSessionRevision + 1,
                        )
                    }
                    openSession(action.sessionId)
                }
            }
            return true
        }
        is AppAction.OpenModelPicker -> {
            modelsModel.openPicker(
                action.sessionId,
                conversations.state.value.chats[action.sessionId]?.model,
            )
            return true
        }
        is AppAction.ChooseModel -> {
            modelsModel.choosePicker(action.selection)
            return true
        }
        AppAction.ConfirmModel -> {
            modelsModel.confirmPicker()
            return true
        }
        AppAction.CloseModelPicker -> {
            modelsModel.dismissPicker()
            return true
        }
        AppAction.ReloadModels -> {
            modelsModel.invalidatePicker()
            return true
        }
        else -> return false
    }
}

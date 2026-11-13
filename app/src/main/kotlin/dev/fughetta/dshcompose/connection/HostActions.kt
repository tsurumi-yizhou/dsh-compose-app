package dev.fughetta.dshcompose.connection

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handleHostActions(action: AppAction): Boolean {
    when (action) {
        AppAction.AddRemoteHost -> {
            if (!state.value.remoteHostsBusy)
                mutableState.update {
                    it.copy(
                        remoteHostEditor =
                            dev.fughetta.dshcompose.domain.hosts.RemoteHost(
                                "compose-${java.util.UUID.randomUUID()}",
                                "",
                                "",
                            ),
                        remoteHostError = null,
                    )
                }
            return true
        }
        AppAction.CloseRemoteHost -> {
            if (!state.value.remoteHostsBusy)
                mutableState.update { it.copy(remoteHostEditor = null, remoteHostError = null) }
            return true
        }
        is AppAction.SaveRemoteHost -> {
            saveRemoteHost(action)
            return true
        }
        is AppAction.RepairRemoteHost -> {
            if (state.value.remoteHostsBusy) return true
            mutableState.update {
                it.copy(
                    remoteHostEditor = it.remoteHosts.firstOrNull { host -> host.id == action.id },
                    remoteHostError = null,
                )
            }
            return true
        }
        is AppAction.RemoveRemoteHost -> {
            removeRemoteHost(action.id, localOnly = false)
            return true
        }
        is AppAction.ForgetRemoteHost -> {
            removeRemoteHost(action.id, localOnly = true)
            return true
        }
        AppAction.OpenTerminal -> {
            mutableState.update { it.copy(openTerminalRevision = it.openTerminalRevision + 1) }
            return true
        }
        is AppAction.Terminal -> {
            terminal.action(action.action)
            return true
        }
        else -> return false
    }
}

private fun AppViewModel.removeRemoteHost(id: String, localOnly: Boolean) {
    if (state.value.remoteHostsBusy) return
    mutableState.update { it.copy(remoteHostsBusy = true) }
    viewModelScope.launch {
        try {
            if (localOnly) hosts.forgetService(id) else hosts.removeService(id)
            dev.fughetta.dshcompose.sync.SessionSyncWork.cancel(app, id)
            mutableState.update {
                it.copy(remoteHosts = it.remoteHosts.filterNot { host -> host.id == id })
            }
            if (selectedHost == id) connectLocal()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update {
                it.copy(error = if (localOnly) "service/forget-failed" else "service/remove-failed")
            }
        } finally {
            mutableState.update { it.copy(remoteHostsBusy = false) }
        }
    }
}

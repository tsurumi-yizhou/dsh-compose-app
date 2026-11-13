package dev.fughetta.dshcompose.notify

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.connection.connectLocal
import dev.fughetta.dshcompose.connection.connectService
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.routeNotification
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.openNotification(
    target: dev.fughetta.dshcompose.ui.notify.NotificationTarget,
): Unit {
    run {
        if (target.host == "local") {
            val owner = client?.takeIf {
                selectedHost == "local" && it.state.value.state == ConnectionState.Connected
            }
            if (owner != null) routeNotification(target, owner)
            else {
                pendingNotification = target
                connectLocal()
            }
            return
        }
        if (
            target.host == selectedHost && client?.state?.value?.state == ConnectionState.Connected
        ) {
            routeNotification(target, client ?: return)
        } else {
            if (state.value.remoteHosts.any { it.id == target.host }) {
                pendingNotification = target
                connectService(target.host)
                return
            }
            mutableState.update { it.copy(error = "notify/host-unavailable") }
        }
    }
}

internal fun AppViewModel.revealLocalSession(id: String): Unit {
    run {
        if (selectedHost == "local" && client?.state?.value?.state == ConnectionState.Connected) {
            mutableState.update {
                it.copy(openSessionId = id, openSessionRevision = it.openSessionRevision + 1)
            }
            openSession(id)
        } else {
            pendingLocalReveal = id
            connectLocal()
        }
    }
}

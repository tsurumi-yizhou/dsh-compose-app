package dev.fughetta.dshcompose

import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.routeNotification(
    target: dev.fughetta.dshcompose.ui.notify.NotificationTarget,
    owner: ConnectedHost,
) {
    if (client !== owner || selectedHost != target.host) return
    revealOwnedSession(owner, target.sessionId)
}

internal fun AppViewModel.revealOwnedSession(owner: ConnectedHost, id: String) {
    if (client !== owner) return
    mutableState.update {
        it.copy(openSessionId = id, openSessionRevision = it.openSessionRevision + 1)
    }
    openSession(id)
}

internal fun AppViewModel.onLocalConnectionChanged() {
    val lease = localObserver.lease
    phoneTurnResults.connection(lease, lease?.generation)
    deepSeekAccount.connect(lease?.account)
    phoneTurnEnd.localConnectionChanged()
    notificationSettings.connect(
        "local",
        true,
        lease?.notifications,
    )
}

internal fun AppViewModel.onLocalRunStarted(id: String) {
    val lease = localObserver.lease ?: return
    phoneTurnResults.started(lease, lease.generation, id)
    phoneTurnEnd.onLocalTurnStarted(id)
}

internal suspend fun AppViewModel.onLocalRunFinished(id: String) {
    backgroundNet.turnEnded()
    val lease = localObserver.lease ?: return
    phoneTurnResults.finished(lease, lease.generation, id) { phoneTurnEnd.onLocalTurnEnd(id) }
}

internal fun AppViewModel.onLocalSummaries(rows: List<SessionSummary>) =
    notificationSettings.sessions("local", rows)

internal fun AppViewModel.onLocalHostEvent(
    event: dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
) {
    notificationSettings.onHostEvent("local", event)
}

internal fun AppViewModel.notificationTap(event: Map<String, Any?>) =
    notificationSettings.onTap(event)

internal fun AppViewModel.notificationResponse(event: Map<String, Any?>) =
    notificationSettings.respondAction(event)

internal fun AppViewModel.phoneFailure(code: String) {
    phoneControls.reportError(code)
    mutableState.update { it.copy(error = code) }
}

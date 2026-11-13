package dev.fughetta.dshcompose.sessions

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.loadDrawerPreferences(host: String) {
    mutableState.update { it.copy(sessionAttention = SessionAttentionState()) }
    drawerUi.load(host)
}

internal fun AppViewModel.markSessionSeen(id: String): Unit {
    run {
        val row =
            sessionRepository.state.value.sessions.firstOrNull { it.sessionId == id } ?: return
        drawerUi.markSeen(id, row.updatedAt)
        refreshSessionAttention()
    }
}

internal fun AppViewModel.refreshSessionAttention(): Unit {
    run {
        attentionTimer?.cancel()
        val owner = client
        val pending =
            owner?.takeIf { state.value.connectionLive }?.requests?.value?.pending.orEmpty()
        val now = android.os.SystemClock.elapsedRealtime()
        pendingSince.keys.retainAll(pending.keys)
        val ripe = mutableListOf<Pair<String, PendingKind>>()
        var remaining: Long? = null
        for ((eventId, event) in pending) {
            val kind =
                when (event.text("event")) {
                    "approval/request" -> PendingKind.Approval
                    "user-questions/request" ->
                        if (
                            owner?.requests?.value?.questions?.get(eventId)?.questions?.any {
                                it.intent?.text("kind") == "plan-review"
                            } == true
                        )
                            PendingKind.Plan
                        else PendingKind.Question
                    else -> continue
                }
            val id = event.text("agentId") ?: continue
            val wait = 800 - (now - pendingSince.getOrPut(eventId) { now })
            if (wait <= 0) ripe += id to kind else remaining = minOf(remaining ?: wait, wait)
        }
        val viewed = visibleSessionId.takeIf { foreground }
        drawerUi.observeSessions(sessionRepository.state.value.sessions, viewed)
        val seen = drawerUi.state.value.seen
        val rows =
            attentionSessions.filter { it.origin == "subagent" } +
                sessionRepository.state.value.sessions
        mutableState.update {
            it.copy(
                sessionAttention =
                    SessionAttentionState(
                        sessionPendingInteractions(ripe, rows),
                        seen
                            .unread(sessionRepository.state.value.sessions, it.selectedSessionId)
                            .toPersistentSet(),
                    ),
            )
        }
        remaining?.let { wait ->
            attentionTimer = viewModelScope.launch {
                delay(wait)
                if (client === owner) refreshSessionAttention()
            }
        }
    }
}

package dev.fughetta.dshcompose.sessions

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.messages.restorePersistedPrompts
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.conversation.toConversationPublication
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.bindConversation(id: String): Unit {
    if (id == DRAFT_SESSION_ID) {
        selectDraft()
        return
    }
    if (id in retiredSessions) return
    watchedId?.takeIf { it != id }?.let(::markSessionSeen)
    mutableState.update { it.copy(selectedSessionId = id) }
    refreshSessionAttention()
    if (watchedId != id) {
        references.dismiss()
        conversationModel.resetMessage()
    }
    if (conversationModel.isActive(id)) return
    val previous = watchedId
    if (previous != null && previous != id) replyLinks.dismissSession(previous)
    watchedId = id
    terminal.connect(connectionSession?.repositories?.terminals, selectedHost, id)
    nativePlugins.session(id)
    mediaGeneration.session(id)
    usage.monitor.connect(connectionSession?.repositories?.usage, selectedHost, id)
    refreshUsageAccount()
    conversationModel.clear()
    // Drafts and attachment choices survive navigation; full histories belong to the active
    // chat.
    if (previous != null && previous != id)
        conversations.releaseTranscript(previous, hosts.nextTranscriptRevision(selectedConnection))
    refreshChatRequests(id)
    val owner = connectionSession
    if (owner == null) {
        bindCachedTranscript(id, selectedHost)
        restorePersistedPrompts(id)
        return
    }
    hosts.subagentRoute(owner, id)?.let { route ->
        updateChat(id) { it.copy(readOnly = route.mode != SubagentMode.Continuable) }
    }
    commands.composer(id, composer.input(id).draft, false)
    hosts.selectSession(selectedConnection, id)
    val transcript =
        hosts.conversation(
            owner,
            id,
            conversations.state.value.chats[id]?.lastCommand?.let { it.result to it.line },
        )
    conversationModel.bind(
        id,
        transcript,
        transcript
            .publications(
                isCurrentConnection = { connectionSession === owner },
                usage = usage.monitor,
            )
            .map { it.toConversationPublication(hosts.subagentRoute(owner, id)) },
    )
    restorePersistedPrompts(id)
}

/** Offline: render the mirrored transcript read-only until a connection can take over. */
private fun AppViewModel.bindCachedTranscript(id: String, host: String) {
    if (conversationModel.isActive(id)) return
    viewModelScope.launch {
        val transcript =
            hosts.cachedConversation(
                selectedConnection,
                host,
                id,
                lastHome(host).orEmpty(),
                CACHED_TRANSCRIPT_LIMIT,
                current = { watchedId == id && client == null },
            ) ?: return@launch
        conversationModel.bind(
            id,
            transcript,
            transcript
                .publications(isCurrentConnection = { watchedId == id && client == null })
                .map { it.toConversationPublication(null) },
        )
    }
}

private const val CACHED_TRANSCRIPT_LIMIT = 200

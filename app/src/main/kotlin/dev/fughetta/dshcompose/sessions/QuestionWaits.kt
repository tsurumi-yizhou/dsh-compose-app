package dev.fughetta.dshcompose.sessions

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.updateQuestionWaits(owner: SessionsRepository?): Unit {
    if (questionWaitOwner !== owner) {
        questionWaits.values.forEach { it.cancel() }
        questionWaits.clear()
        questionRemaining.clear()
        questionWaitOwner = owner
    }
    if (owner == null) return
    run {
        val wanted =
            client
                ?.requests
                ?.value
                ?.takeIf { it.generation == owner.generation }
                ?.questions
                .orEmpty()
                .filterValues { it.timed && it.waitCallId != null }
        questionWaits.keys
            .filter { it !in wanted }
            .forEach { event ->
                questionWaits.remove(event)?.cancel()
                questionRemaining.remove(event)
            }
        val generation = owner.generation ?: return
        for ((eventId, request) in wanted) if (eventId !in questionWaits) {
            questionWaits[eventId] = viewModelScope.launch {
                try {
                    owner.remaining(request.sessionId, request.waitCallId!!).collect { remaining ->
                        if (owner.active && owner.generation == generation && remaining != null) {
                            questionRemaining[eventId] = remaining
                            refreshChatRequests(request.sessionId)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (owner.active && owner.generation == generation && owner.pending(eventId))
                        updateChat(request.sessionId) { it.copy(error = error.message) }
                }
            }
        }
    }
}

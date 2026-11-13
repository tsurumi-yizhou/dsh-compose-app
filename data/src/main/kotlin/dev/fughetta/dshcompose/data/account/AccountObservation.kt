package dev.fughetta.dshcompose.data.account

import dev.fughetta.dshcompose.domain.account.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Re-read before following after an interruption, with the same bounded retry delay. */
fun accountUpdates(
    owner: AccountRepository,
    current: () -> Boolean,
    onFailure: (Exception) -> Unit,
): Flow<DeepSeekAccountView> = flow {
    var delayMs = 2000L
    while (current()) {
        try {
            emit(owner.state())
            owner.watch().collect {
                emit(it)
                delayMs = 2000
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current()) onFailure(error)
        }
        delay(delayMs)
        delayMs = (delayMs * 2).coerceAtMost(30000)
    }
}

/** Complete an in-flight sign-in request so a withdrawn attempt can be cancelled on the host. */
suspend fun beginAccountSignIn(
    owner: AccountRepository,
    accepted: () -> Boolean,
    canCancel: () -> Boolean,
): DeepSeekAccountView? =
    withContext(NonCancellable) {
        val view = owner.signIn()
        if (accepted()) view
        else {
            val id = view.attempt?.takeIf { it.phase.active }?.id
            if (id != null && canCancel()) {
                try {
                    owner.cancel(id)
                } catch (_: Exception) {}
            }
            null
        }
    }

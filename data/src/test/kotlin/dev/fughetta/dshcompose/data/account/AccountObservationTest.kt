package dev.fughetta.dshcompose.data.account

import dev.fughetta.dshcompose.domain.account.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountObservationTest {
    @Test
    fun cancelledSignInStillCancelsTheAttemptWhenTheHostReplies() = runTest {
        val response = CompletableDeferred<DeepSeekAccountView>()
        val cancelled = mutableListOf<String>()
        val owner =
            object : AccountRepository {
                override fun updates(current: () -> Boolean, onFailure: (Exception) -> Unit) =
                    accountUpdates(this, current, onFailure)

                override suspend fun beginSignIn(
                    accepted: () -> Boolean,
                    canCancel: () -> Boolean,
                ) = beginAccountSignIn(this, accepted, canCancel)

                override val generation = Any()

                override suspend fun state() = error("Unused")

                override fun watch() = emptyFlow<DeepSeekAccountView>()

                override suspend fun signIn() = response.await()

                override suspend fun cancel(id: String): DeepSeekAccountView {
                    cancelled += id
                    return DeepSeekAccountView(false, null, null, null)
                }

                override suspend fun signOut() = error("Unused")

                override suspend fun profile(): DeepSeekAccountProfile? = null

                override suspend fun balance(): DeepSeekAccountBalance? = null

                override suspend fun runningTasks() = false

                override suspend fun initializeDefaultModel() = Unit
            }
        var accepted = true
        val request = async { beginAccountSignIn(owner, { accepted }, { true }) }
        runCurrent()
        accepted = false
        request.cancel()
        response.complete(
            DeepSeekAccountView(
                false,
                null,
                null,
                AccountSignInAttempt(
                    "attempt",
                    AccountSignInPhase.WaitingBrowser,
                    null,
                    null,
                    null,
                ),
            ),
        )
        request.join()
        assertEquals(listOf("attempt"), cancelled)
    }
}

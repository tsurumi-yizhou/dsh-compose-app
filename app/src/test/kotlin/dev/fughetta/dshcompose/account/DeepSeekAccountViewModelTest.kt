package dev.fughetta.dshcompose.account

import dev.fughetta.dshcompose.domain.account.*
import dev.fughetta.dshcompose.ui.account.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeepSeekAccountViewModelTest {
    @Test
    fun returningFromBrowserCompletesSignInWithoutAWatchEvent() = runTest {
        val repository = SilentAccountRepository()
        val model = DeepSeekAccountViewModel(backgroundScope)
        model.connect(repository)
        model.action(DeepSeekAccountAction.SignIn)
        runCurrent()
        assertEquals(DeepSeekAccountPhase.SigningIn, model.state.value.phase)
        model.foreground(false)
        model.foreground(true)
        runCurrent()
        assertEquals(1, repository.reads)

        // The browser has returned, but the engine finishes exchanging the grant later.
        repository.view = DeepSeekAccountView(true, null, null, null)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(DeepSeekAccountPhase.SignedIn, model.state.value.phase)
        assertTrue(model.identityFor(repository.generation))
        assertEquals("Account", model.state.value.profile?.name)
        assertEquals(1, repository.initializations)
        val reads = repository.reads
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(reads, repository.reads)
        model.close()
    }

    private class SilentAccountRepository : AccountRepository {
        override val generation = Any()
        var reads = 0
        var initializations = 0
        var view =
            DeepSeekAccountView(
                false,
                null,
                null,
                AccountSignInAttempt(
                    "attempt",
                    AccountSignInPhase.WaitingBrowser,
                    "https://example.com/authorize",
                    System.currentTimeMillis() + 600_000,
                    null,
                ),
            )

        override fun updates(
            current: () -> Boolean,
            onFailure: (Exception) -> Unit,
        ): Flow<DeepSeekAccountView> = emptyFlow()

        override suspend fun beginSignIn(accepted: () -> Boolean, canCancel: () -> Boolean) = view

        override suspend fun state(): DeepSeekAccountView {
            reads++
            return view
        }

        override fun watch(): Flow<DeepSeekAccountView> = emptyFlow()

        override suspend fun signIn() = view

        override suspend fun cancel(id: String) = view

        override suspend fun signOut() = view

        override suspend fun profile() = DeepSeekAccountProfile("id", "Account", null, null)

        override suspend fun balance() = DeepSeekAccountBalance(emptyList(), emptyList())

        override suspend fun runningTasks() = false

        override suspend fun initializeDefaultModel() {
            initializations++
        }
    }
}

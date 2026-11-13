package dev.fughetta.dshcompose.ui.sessions

import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private class Catalog : dev.fughetta.dshcompose.testing.TestSessionsRepository() {
        override val state =
            MutableStateFlow(SessionCatalogState(available = true, initialized = true))
        val replies = Channel<Result<PersistentList<SessionSummary>>>(Channel.UNLIMITED)
        var requests = 0

        override fun get(id: String) = state.value.sessions.firstOrNull { it.sessionId == id }

        override suspend fun refresh() {
            requests++
            state.value = state.value.copy(loading = true)
            val result = replies.receive()
            val failure = result.exceptionOrNull() as? SessionOperationException
            state.value =
                state.value.copy(
                    loading = false,
                    failure = failure,
                    sessions = result.getOrNull() ?: state.value.sessions,
                )
            if (failure != null) throw failure
        }
    }

    @Test
    fun refreshSurvivesRecreationSharesInFlightWorkAndPublishesFailureAndRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val repository = Catalog()
        val provider =
            ViewModelProvider(
                store,
                viewModelFactory { initializer { SessionsViewModel(repository) } },
            )
        val model = provider[SessionsViewModel::class.java]
        try {
            assertTrue(model.handle(AppAction.RefreshSessions))
            assertTrue(model.handle(AppAction.RefreshSessions))
            runCurrent()
            assertEquals(1, repository.requests)
            assertTrue(model.uiState.value.loading)
            assertSame(model, provider[SessionsViewModel::class.java])
            repository.replies.send(
                Result.failure(SessionOperationException("session/list-denied")),
            )
            runCurrent()
            assertEquals("session/list-denied", model.uiState.value.failure!!.code)
            assertFalse(model.uiState.value.loading)
            model.handle(AppAction.RefreshSessions)
            runCurrent()
            val row =
                SessionSummary("session", "Confirmed", null, false, false, true, null, null, 1)
            repository.replies.send(Result.success(persistentListOf(row)))
            runCurrent()
            assertNull(model.uiState.value.failure)
            assertSame(row, model.uiState.value.sessions.single())
            repository.state.value = SessionCatalogState(loading = true)
            runCurrent()
            assertTrue(model.uiState.value.loading)
            assertFalse(model.handle(AppAction.RefreshSessions))
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}

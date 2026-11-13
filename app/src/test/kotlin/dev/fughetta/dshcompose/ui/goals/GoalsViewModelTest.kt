package dev.fughetta.dshcompose.ui.goals

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoalsViewModelTest {
    private fun state(session: String, revision: Long = 1) =
        GoalState(
            session,
            GoalProjection(
                GoalSnapshot(GoalRef("goal", revision), "Objective", GoalPhase.Active, 256, null),
                0,
                1,
                2,
            ),
            GoalActivation.Disarmed,
            true,
            true,
        )

    private class Repository(initial: GoalState) : GoalsRepository {
        override fun transcript(
            id: String,
            projection: GoalProjection?,
            isRunning: Boolean,
            ready: Boolean,
        ) = error("unused")

        override val state = MutableStateFlow<GoalState?>(initial)
        var submitted: Pair<GoalRef, String>? = null
        var observed: Pair<String, Boolean>? = null

        override fun observe(sessionId: String, observing: Boolean) {
            observed = sessionId to observing
        }

        override fun refresh() {}

        override suspend fun edit(owner: Long, ref: GoalRef, objective: String) {
            submitted = ref to objective
            state.value = state.value?.copy(error = "GOAL_STALE_REVISION")
        }

        override suspend fun change(owner: Long, ref: GoalRef, action: GoalAction) {}

        override suspend fun clear(owner: Long, ref: GoalRef) {
            state.value = state.value?.copy(projection = null, mutationRevision = 1)
        }
    }

    @Test
    fun staleEditsKeepTheirOriginalRevisionAndTextUntilSuccessOrSessionChange() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = Repository(state("session"))
            val provider =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { GoalsViewModel(repository) } },
                )
            val model = provider[GoalsViewModel::class.java]
            runCurrent()
            model.handle(AppAction.ObserveGoal("session", true))
            assertEquals("session" to true, repository.observed)
            model.handle(AppAction.ShowGoal)
            model.handle(AppAction.EditGoal)
            model.handle(AppAction.ChangeGoalObjective(" User revision "))
            repository.state.value = state("session", 2)
            runCurrent()
            model.handle(AppAction.SaveGoal)
            runCurrent()
            assertEquals(GoalRef("goal", 1) to "User revision", repository.submitted)
            assertEquals(" User revision ", model.uiState.value!!.editor!!.text)
            assertNotNull(model.uiState.value!!.error)
            assertTrue(model.uiState.value!!.dialog)
            repository.state.value =
                repository.state.value!!.copy(error = null, mutationRevision = 1)
            runCurrent()
            assertNull(model.uiState.value!!.editor)
            model.handle(AppAction.EditGoal)
            repository.state.value = repository.state.value!!.copy(owner = 1)
            runCurrent()
            assertNull(model.uiState.value!!.editor)
            assertFalse(model.uiState.value!!.dialog)
            model.handle(AppAction.EditGoal)
            repository.state.value = state("another-session")
            runCurrent()
            assertEquals("another-session", model.uiState.value!!.sessionId)
            assertNull(model.uiState.value!!.editor)
            assertFalse(model.uiState.value!!.dialog)
            assertSame(model, provider[GoalsViewModel::class.java])
            repository.state.value = null
            runCurrent()
            assertNull(model.uiState.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

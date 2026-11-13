package dev.fughetta.dshcompose.ui.schedules

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.schedules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulesViewModelTest {
    private fun task(id: String) =
        ScheduleTask(
            id,
            id,
            "prompt",
            "every",
            "active",
            ScheduleTarget(),
            emptyList(),
            "revision",
            null,
            null,
            ScheduleDraft(repeat = ScheduleRepeat.Interval),
        )

    private class Operations(var rows: List<ScheduleTask>) : SchedulesRepository {
        override var active = true
        override val canManage = true
        override val changes = MutableSharedFlow<Unit>()
        var reads = 0
        var listGate: CompletableDeferred<Unit>? = null
        var failure: ScheduleOperationException? = null
        var historyGate: CompletableDeferred<Unit>? = null

        override fun invalidate() {}

        override suspend fun list(active: Boolean?, offset: Int): SchedulePage {
            reads++
            val snapshot = rows
            listGate?.let { withContext(NonCancellable) { it.await() } }
            return SchedulePage(snapshot, snapshot.size, offset, snapshot.size)
        }

        override suspend fun catalog() = rows

        override suspend fun history(id: String, before: String?): ScheduleHistory {
            historyGate?.let { withContext(NonCancellable) { it.await() } }
            return ScheduleHistory(
                listOf(ScheduleRun(id, null, null, "delivered", id)),
                null,
                false,
                false,
                null,
                null,
            )
        }

        override suspend fun create(input: ScheduleInput): ScheduleTask {
            failure?.let { throw it }
            return rows.first()
        }

        override suspend fun update(task: ScheduleTask, changes: ScheduleChanges): ScheduleTask {
            failure?.let { throw it }
            return task.copy(title = changes.title ?: task.title)
        }

        override suspend fun delete(task: ScheduleTask) {}

        override suspend fun run(task: ScheduleTask) {}
    }

    @Test
    fun pageLifecycleRefreshesAndHostSwitchDiscardsOldHistoryAndNavigation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val old = Operations(listOf(task("old")))
            val connection = MutableStateFlow<SchedulesRepository?>(old)
            val repository = connection.asStateFlow()
            val provider =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { SchedulesViewModel(repository, dispatcher) } },
                )
            val model = provider[SchedulesViewModel::class.java]
            runCurrent()
            assertEquals(0, old.reads)
            model.dispatch(SchedulesAction.Observe(true))
            runCurrent()
            assertEquals("old", model.uiState.value.rows.single().id)
            old.historyGate = CompletableDeferred()
            model.dispatch(SchedulesAction.OpenTask("old"))
            runCurrent()
            model.dispatch(SchedulesAction.OpenSession("old"))
            assertEquals("old", model.uiState.value.openSessionId)
            old.active = false
            val next = Operations(listOf(task("new")))
            connection.value = next
            assertNull(model.consumeOpenSession())
            runCurrent()
            old.historyGate!!.complete(Unit)
            runCurrent()
            assertEquals("new", model.uiState.value.rows.single().id)
            assertNull(model.uiState.value.detail)
            next.listGate = CompletableDeferred()
            model.dispatch(SchedulesAction.Refresh)
            runCurrent()
            assertEquals(2, next.reads)
            model.dispatch(SchedulesAction.Observe(false))
            next.rows = listOf(task("changed"))
            next.changes.emit(Unit)
            runCurrent()
            next.listGate!!.complete(Unit)
            runCurrent()
            assertEquals("new", model.uiState.value.rows.single().id)
            assertFalse(model.uiState.value.loading)
            model.dispatch(SchedulesAction.Observe(true))
            runCurrent()
            assertEquals(3, next.reads)
            assertEquals("changed", model.uiState.value.rows.single().id)
            assertSame(model, provider[SchedulesViewModel::class.java])
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun engineRefusalKeepsTheEditorEditableButUnknownWriteOutcomeBlocksResubmission() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val operations = Operations(listOf(task("task")))
            val repository = MutableStateFlow<SchedulesRepository?>(operations)
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { SchedulesViewModel(repository, dispatcher) } },
                )[SchedulesViewModel::class.java]
            runCurrent()
            model.dispatch(SchedulesAction.Observe(true))
            runCurrent()
            model.dispatch(SchedulesAction.OpenTask("task"))
            runCurrent()
            model.dispatch(SchedulesAction.Edit)
            model.dispatch(SchedulesAction.Title("edited"))
            runCurrent()
            operations.failure = ScheduleOperationException("schedule_conflict", rejected = true)
            model.dispatch(SchedulesAction.Save)
            runCurrent()
            assertEquals("schedule_conflict", model.uiState.value.error)
            assertFalse(model.uiState.value.editor!!.uncertain)
            operations.failure = ScheduleOperationException("request_failed")
            model.dispatch(SchedulesAction.Save)
            runCurrent()
            assertTrue(model.uiState.value.editor!!.uncertain)
            model.dispatch(SchedulesAction.Title("must not change"))
            assertEquals("edited", model.uiState.value.editor!!.title)
            assertFalse(model.uiState.value.busy)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

package dev.fughetta.dshcompose.ui.models

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {
    private val selected = ModelSelection("provider", "model")
    private val initial = ModelDefaults(17, selected, true, null, true)
    private val catalog =
        ModelCatalog(
            selected,
            persistentSetOf("provider"),
            persistentListOf(
                ProviderGroup(
                    "provider",
                    "Provider",
                    persistentListOf(
                        ModelInfo("provider", "model", "Model", null, persistentListOf(), null),
                    ),
                ),
            ),
            persistentListOf(),
        )

    private inner class Operations : SessionModelsRepository {
        override val changes = MutableStateFlow(0L)
        var snapshot = initial
        var save: suspend (Boolean) -> Unit = {
            snapshot = initial.copy(revision = 18, rememberLastSelection = it)
        }

        override suspend fun defaults() = snapshot

        override suspend fun setDefaults(
            previous: ModelDefaults,
            selection: ModelSelection,
            remember: Boolean,
        ) = save(remember)

        override suspend fun catalog() = catalog

        override suspend fun select(
            sessionId: String,
            selection: ModelSelection,
        ): ModelSelectionResult = error("Unused")

        override fun invalidate() {
            changes.value++
        }
    }

    private class Repository(initial: Operations) {
        val connection = MutableStateFlow<SessionModelsRepository?>(initial)
    }

    @Test
    fun conflictKeepsTheEditorUntilAnExplicitRefreshAndInvalidationDisablesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val operations = Operations()
            val repository = Repository(operations)
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { ModelsViewModel(repository.connection) } },
                )[ModelsViewModel::class.java]
            model.handle(AppAction.ObserveModelDefaults(true))
            runCurrent()
            model.handle(AppAction.OpenDefaultModelPicker)
            operations.save = { throw ModelOperationException("settings/conflict") }
            model.handle(AppAction.ConfirmDefaultModel)
            runCurrent()
            assertTrue(model.uiState.value.editor!!.stale)
            assertEquals("models/default-stale", model.uiState.value.editor!!.error)
            model.handle(AppAction.RefreshModelDefaults)
            runCurrent()
            assertFalse(model.uiState.value.editor!!.stale)
            operations.invalidate()
            runCurrent()
            assertTrue(model.uiState.value.editor!!.stale)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hidingDoesNotCancelAWriteAndAReplacedHostRejectsItsLateResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val old = Operations()
            val repository = Repository(old)
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { ModelsViewModel(repository.connection) } },
                )[ModelsViewModel::class.java]
            model.handle(AppAction.ObserveModelDefaults(true))
            runCurrent()
            val acknowledged = CompletableDeferred<Unit>()
            old.save = { remember ->
                acknowledged.await()
                old.snapshot = initial.copy(revision = 18, rememberLastSelection = remember)
            }
            model.handle(AppAction.ChangeDefaultModelMode(false))
            runCurrent()
            model.handle(AppAction.ObserveModelDefaults(false))
            acknowledged.complete(Unit)
            runCurrent()
            assertFalse(model.uiState.value.snapshot!!.rememberLastSelection)
            assertEquals("models/default-saved", model.uiState.value.notice)
            model.handle(AppAction.ObserveModelDefaults(true))
            runCurrent()
            val late = CompletableDeferred<Unit>()
            old.save = { withContext(NonCancellable) { late.await() } }
            model.handle(AppAction.ChangeDefaultModelMode(true))
            runCurrent()
            val next = Operations().apply { snapshot = initial.copy(revision = 100) }
            repository.connection.value = next
            runCurrent()
            late.complete(Unit)
            runCurrent()
            assertEquals(100L, model.uiState.value.snapshot!!.revision)
            assertNull(model.uiState.value.notice)
            assertFalse(model.uiState.value.busy)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

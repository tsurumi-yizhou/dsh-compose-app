package dev.fughetta.dshcompose.ui.models

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.DRAFT_SESSION_ID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerViewModelTest {
    private val selection = ModelSelection("provider", "model", "medium")
    private val catalog =
        ModelCatalog(
            selection,
            persistentSetOf("provider"),
            persistentListOf(
                ProviderGroup(
                    "provider",
                    "Provider",
                    persistentListOf(
                        ModelInfo(
                            "provider",
                            "model",
                            "Model",
                            null,
                            persistentListOf(ReasoningEffort("medium", "Medium", null)),
                            "medium",
                        ),
                    ),
                ),
            ),
            persistentListOf(),
        )

    private class Operations(private val catalog: ModelCatalog) : SessionModelsRepository {
        override val changes = MutableStateFlow(0L)

        override suspend fun defaults(): ModelDefaults? = null

        override suspend fun setDefaults(
            previous: ModelDefaults,
            selection: ModelSelection,
            remember: Boolean,
        ) = Unit

        var selections = 0
        var answer: suspend () -> ModelSelectionResult = {
            ModelSelectionResult(ModelSelection("host", "confirmed"), ModelDefaultSaveStatus.Failed)
        }

        override suspend fun catalog() = catalog

        override suspend fun select(
            sessionId: String,
            selection: ModelSelection,
        ): ModelSelectionResult {
            selections++
            return answer()
        }

        override fun invalidate() {}
    }

    private class Repository(initial: Operations) {
        val connection = MutableStateFlow<SessionModelsRepository?>(initial)
    }

    private fun create(
        store: ViewModelStore,
        repository: StateFlow<SessionModelsRepository?>,
        parent: CoroutineScope,
        onSelected: suspend (String, SessionModelsRepository, ModelSelectionResult) -> Unit,
        onDraftSelected: (ModelSelection) -> Unit,
    ): ModelsViewModel =
        ViewModelProvider(
            store,
            viewModelFactory {
                initializer { ModelsViewModel(repository, parent, onSelected, onDraftSelected) }
            },
        )[ModelsViewModel::class.java]

    @Test
    fun draftChoiceStaysLocalAndUnavailableSelectionsAreIgnored() = runTest {
        val operations = Operations(catalog)
        val repository = Repository(operations)
        var chosen: ModelSelection? = null
        val store = ViewModelStore()
        val controller =
            create(
                store,
                repository.connection,
                backgroundScope,
                { _, _, _ -> fail("Draft must not select a host model") },
                { chosen = it },
            )
        controller.openPicker(DRAFT_SESSION_ID, null)
        runCurrent()
        controller.choosePicker(ModelSelection("provider", "missing"))
        assertEquals(selection, controller.pickerUiState.value!!.candidate)
        controller.confirmPicker()
        assertEquals(selection, chosen)
        assertNull(controller.pickerUiState.value)
        assertEquals(0, operations.selections)
        store.clear()
    }

    @Test
    fun confirmationsUseHostReceiptsAndRetiredDialogsCannotApplyLateResults() = runTest {
        val old = Operations(catalog)
        val finish = CompletableDeferred<ModelSelectionResult>()
        old.answer = { withContext(NonCancellable) { finish.await() } }
        val repository = Repository(old)
        val applied = mutableListOf<ModelSelectionResult>()
        val store = ViewModelStore()
        val controller =
            create(
                store,
                repository.connection,
                backgroundScope,
                { _, _, result -> applied += result },
                {},
            )
        controller.openPicker("session", selection)
        runCurrent()
        controller.confirmPicker()
        runCurrent()
        controller.dismissPicker()
        assertTrue(controller.pickerUiState.value!!.busy)
        val next = Operations(catalog)
        repository.connection.value = next
        runCurrent()
        finish.complete(ModelSelectionResult(selection, ModelDefaultSaveStatus.Saved))
        runCurrent()
        assertTrue(applied.isEmpty())
        assertNull(controller.pickerUiState.value)
        controller.openPicker("session", selection)
        runCurrent()
        controller.confirmPicker()
        runCurrent()
        assertEquals(ModelSelection("host", "confirmed"), applied.single().selected)
        assertEquals(ModelDefaultSaveStatus.Failed, applied.single().defaultSave)
        assertNull(controller.pickerUiState.value)
        store.clear()
    }
}

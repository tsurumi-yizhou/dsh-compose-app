package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateModelDefaultsUseCaseTest {
    private val selected = ModelSelection("provider", "model", "high")
    private val original = ModelDefaults(17, selected, true, "high", true)
    private val catalog =
        ModelCatalog(
            selected,
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
                            persistentListOf(ReasoningEffort("high", "High", null)),
                            "high",
                        ),
                    ),
                ),
            ),
            persistentListOf(),
        )

    private class Operations(var result: ModelDefaults?) : SessionModelsRepository {
        override val changes = MutableStateFlow(0L)
        var written: Triple<ModelDefaults, ModelSelection, Boolean>? = null
        var writeFailure: Exception? = null
        var readFailure: Exception? = null

        override suspend fun defaults(): ModelDefaults? {
            readFailure?.let { throw it }
            return result
        }

        override suspend fun setDefaults(
            previous: ModelDefaults,
            selection: ModelSelection,
            remember: Boolean,
        ) {
            writeFailure?.let { throw it }
            written = Triple(previous, selection, remember)
        }

        override suspend fun catalog(): ModelCatalog = error("Unused")

        override suspend fun select(
            sessionId: String,
            selection: ModelSelection,
        ): ModelSelectionResult = error("New-chat defaults must not switch a session")

        override fun invalidate() {}
    }

    @Test
    fun onlyTheMatchingHostReadBackConfirmsTheRevisionedWrite() = runBlocking {
        val host = original.copy(revision = 18, rememberLastSelection = false)
        val operations = Operations(host)
        val result = UpdateModelDefaultsUseCase()(operations, original, catalog, selected, false)
        assertEquals(Triple(original, selected, false), operations.written)
        assertEquals(ModelDefaultsUpdateResult(host, true), result)
        operations.result = original
        assertEquals(
            ModelDefaultsUpdateResult(original, false),
            UpdateModelDefaultsUseCase()(operations, original, catalog, selected, false),
        )
        operations.readFailure = IllegalStateException("Read failed after acknowledgment")
        assertEquals(
            ModelDefaultsUpdateResult(null, false),
            UpdateModelDefaultsUseCase()(operations, original, catalog, selected, false),
        )
    }

    @Test
    fun conflictIsAWriteFailureAndIsNotRetried() = runBlocking {
        val operations =
            Operations(original).apply {
                writeFailure = ModelOperationException("settings/conflict")
            }
        try {
            UpdateModelDefaultsUseCase()(operations, original, catalog, selected, false)
            fail()
        } catch (error: ModelOperationException) {
            assertEquals("settings/conflict", error.code)
            assertNull(operations.written)
        }
    }

    @Test
    fun readOnlyUnavailableAndInheritedEffortChangesNeverWrite() = runBlocking {
        val operations = Operations(original)
        for ((snapshot, choice, code) in
            listOf(
                Triple(original.copy(writable = false), selected, "models/default-read-only"),
                Triple(original, selected.copy(model = "removed"), "models/default-unavailable"),
                Triple(
                    original,
                    selected.copy(reasoningEffort = null),
                    "models/default-inherited-effort",
                ),
            )) {
            try {
                UpdateModelDefaultsUseCase()(operations, snapshot, catalog, choice, false)
                fail()
            } catch (error: ModelOperationException) {
                assertEquals(code, error.code)
                assertNull(operations.written)
            }
        }
    }
}

package dev.fughetta.dshcompose.domain.providers

import kotlinx.coroutines.CancellationException

data class ModelDefaultsUpdateResult(val snapshot: ModelDefaults?, val confirmed: Boolean)

/** Keep the host revision and distinguish an acknowledged write from its read-back. */
class UpdateModelDefaultsUseCase {
    suspend operator fun invoke(
        operations: SessionModelsRepository,
        original: ModelDefaults,
        catalog: ModelCatalog,
        selection: ModelSelection,
        remember: Boolean,
    ): ModelDefaultsUpdateResult {
        if (!original.writable) throw ModelOperationException("models/default-read-only")
        if (
            selection.provider.isBlank() || selection.model.isBlank() || !catalog.accepts(selection)
        )
            throw ModelOperationException("models/default-unavailable")
        if (selection.reasoningEffort == null && original.inheritedEffort != null)
            throw ModelOperationException("models/default-inherited-effort")
        operations.setDefaults(original, selection, remember)
        val snapshot =
            try {
                operations.defaults()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        return ModelDefaultsUpdateResult(
            snapshot,
            snapshot?.selection == selection && snapshot.rememberLastSelection == remember,
        )
    }
}

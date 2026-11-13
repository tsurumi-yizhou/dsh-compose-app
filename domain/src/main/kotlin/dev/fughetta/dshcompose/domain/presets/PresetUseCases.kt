package dev.fughetta.dshcompose.domain.presets

import kotlinx.coroutines.CancellationException

class SetDefaultPresetUseCase {
    suspend operator fun invoke(
        operations: PresetsRepository,
        id: String,
    ): AgentPresetCatalog {
        val defaults =
            operations.defaults()?.takeIf { it.writable }
                ?: throw PresetOperationException("settings/read-only")
        operations.setDefault(id, defaults)
        return operations.catalog()
    }
}

data class RemovedPreset(val catalog: AgentPresetCatalog, val restartRequired: Boolean)

class RemovePresetUseCase {
    suspend operator fun invoke(
        operations: PresetsRepository,
        id: String,
        expectedPackage: String,
    ): RemovedPreset {
        if (!operations.supportsRemoval)
            throw PresetOperationException("preset/removal-unavailable")
        val current = operations.catalog()
        if (
            current.presets.none { it.id == id && !it.isDefault } ||
                agentPresetOwner(id, current.bundles)?.name != expectedPackage
        )
            throw PresetOperationException("preset/changed")
        val result = operations.remove(expectedPackage)
        return RemovedPreset(operations.catalog(), result.restartRequired)
    }
}

data class PresetSessionHandoff(val sessionId: String, val error: String? = null)

class CreatePresetSessionUseCase {
    suspend operator fun invoke(
        operations: PresetsRepository,
        text: String,
        timeZone: String,
    ): PresetSessionHandoff {
        val id = operations.createSession()
        try {
            if (operations.select(id, "cordis") != "cordis")
                throw PresetOperationException("preset/creator-not-selected")
            operations.prompt(id, text, timeZone)
            return PresetSessionHandoff(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return PresetSessionHandoff(
                id,
                (error as? PresetOperationException)?.code ?: "preset/handoff-failed",
            )
        }
    }
}

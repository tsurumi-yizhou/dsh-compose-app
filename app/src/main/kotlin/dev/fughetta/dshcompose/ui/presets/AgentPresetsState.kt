package dev.fughetta.dshcompose.ui.presets

import dev.fughetta.dshcompose.domain.presets.*
import kotlinx.collections.immutable.*

enum class PresetEditMode {
    Create,
    Duplicate,
    Edit,
}

enum class PresetField {
    Name,
    Id,
    Description,
    Prompt,
}

data class PresetEditorValues(
    val name: String = "",
    val id: String = "",
    val description: String = "",
    val base: String = "standard",
    val prompt: String = "",
    val complete: Boolean = false,
    val enabled: PersistentSet<String> = persistentSetOf(),
)

data class PresetEditorState(
    val nonce: Long,
    val mode: PresetEditMode,
    val source: AgentPreset?,
    val bundle: PresetInstallation?,
    val values: PresetEditorValues,
    val initial: PresetEditorValues = values,
    val composition: PresetComposition? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val idTouched: Boolean = false,
    val error: String? = null,
    val discard: Boolean = false,
    val createdSessionId: String? = null,
    val initialCompositionLoaded: Boolean = false,
) {
    val dirty
        get() = values != initial
}

data class AgentPresetDetail(
    val nonce: Long,
    val preset: AgentPreset,
    val document: AgentPresetDocument? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class AgentPresetRemoval(
    val preset: AgentPreset,
    val bundle: PresetInstallation,
    val busy: Boolean = false,
    val error: String? = null,
)

data class AgentPresetsUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val writing: Boolean = false,
    val presets: PersistentList<AgentPreset> = persistentListOf(),
    val bundles: List<PresetInstallation> = emptyList(),
    val bundlesError: String? = null,
    val supportsRemoval: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val editor: PresetEditorState? = null,
    val detail: AgentPresetDetail? = null,
    val removal: AgentPresetRemoval? = null,
) {
    val creatorAvailable
        get() = presets.any { it.id == "cordis" && it.broken == null }
}

package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.*

data class ReasoningEffort(val id: String, val name: String, val description: String?)

data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val description: String?,
    val efforts: PersistentList<ReasoningEffort>,
    val defaultEffort: String?,
) {
    val initialEffort: String?
        get() =
            defaultEffort?.takeIf { value -> efforts.any { it.id == value } }
                ?: efforts.firstOrNull { it.id == "medium" }?.id
                ?: efforts.firstOrNull { it.id != "off" && it.id != "none" }?.id
                ?: efforts.firstOrNull()?.id
}

data class ProviderGroup(val id: String, val name: String, val models: PersistentList<ModelInfo>)

data class ModelCatalogFailure(val provider: String, val name: String)

data class ModelCatalog(
    val defaultSelection: ModelSelection?,
    val routableProviders: PersistentSet<String>,
    val groups: PersistentList<ProviderGroup>,
    val failures: PersistentList<ModelCatalogFailure>,
) {
    fun find(selection: ModelSelection): ModelInfo? =
        groups
            .firstOrNull { it.id == selection.provider }
            ?.models
            ?.firstOrNull { it.id == selection.model }

    fun accepts(selection: ModelSelection): Boolean =
        selection.provider in routableProviders &&
            find(selection)?.let { model ->
                selection.reasoningEffort == null ||
                    model.efforts.any { it.id == selection.reasoningEffort }
            } == true
}

enum class ModelDefaultSaveStatus {
    Saved,
    Skipped,
    Failed,
    Unknown,
}

data class ModelSelectionResult(
    val selected: ModelSelection,
    val defaultSave: ModelDefaultSaveStatus,
)

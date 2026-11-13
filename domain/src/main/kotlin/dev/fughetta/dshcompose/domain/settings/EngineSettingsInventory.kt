package dev.fughetta.dshcompose.domain.settings

import dev.fughetta.dshcompose.domain.plugins.*
import kotlinx.collections.immutable.PersistentList

data class EngineSettingsInventory(
    val description: SettingsDocument,
    val bundles: List<PluginBundle>,
    val complete: Result<PersistentList<PluginConfigEntrySummary>>,
)

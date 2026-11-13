package dev.fughetta.dshcompose.domain.plugins

import kotlinx.collections.immutable.PersistentList

data class PluginInventory(
    val bundles: List<PluginBundle>,
    val entries: PersistentList<PluginEntry>,
    val cordis: PersistentList<CordisPlugin>,
    val configurable: Set<String>,
    val cordisUnavailable: Boolean = false,
    val configUnavailable: Boolean = false,
)

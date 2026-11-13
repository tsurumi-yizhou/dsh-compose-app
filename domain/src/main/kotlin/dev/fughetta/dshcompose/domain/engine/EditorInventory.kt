package dev.fughetta.dshcompose.domain.engine

import dev.fughetta.dshcompose.domain.text.LocalizedText
import kotlinx.collections.immutable.*

data class WebBootEntry(
    val id: String,
    val url: String,
    val revision: String,
    val inject: List<String>,
    val external: List<String>,
    val immediately: Boolean,
)

data class WebBootGraph(
    val revision: String,
    val release: String?,
    val entries: PersistentList<WebBootEntry>,
)

data class ClientSlotOccupant(
    val slot: String,
    val packageName: String,
    val cell: String,
    val order: Long,
    val label: LocalizedText?,
    val official: Boolean,
)

data class BridgeClientPackage(
    val name: String,
    val hasClient: Boolean,
    val revision: String?,
    val panels: List<String>,
    val pet: String?,
)

data class BridgeClientInventory(
    val bootRevision: String?,
    val release: String?,
    val packages: PersistentList<BridgeClientPackage>,
    val slots: PersistentMap<String, PersistentList<ClientSlotOccupant>>?,
    val routeGuard: Boolean,
    /** App↔Engine bridge contract version (0 when the host predates it). */
    val contract: Int = 0,
    /** Capabilities the host bridge advertised; empty on older hosts. */
    val capabilities: List<String> = emptyList(),
)

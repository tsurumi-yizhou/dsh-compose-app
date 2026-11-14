package dev.fughetta.dshcompose.ui.engine_settings

import dev.fughetta.dshcompose.domain.engine.BridgeClientPackage
import dev.fughetta.dshcompose.domain.engine.ClientSlotOccupant
import kotlinx.collections.immutable.*

data class PluginSeatsState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val webReachable: Boolean = false,
    val routeGuard: Boolean = false,
    val occupancyCurrent: Boolean = false,
    val webShell: Boolean = false,
    val packages: PersistentList<BridgeClientPackage> = persistentListOf(),
    val occupants: PersistentList<ClientSlotOccupant> = persistentListOf(),
    val error: String? = null,
)

sealed interface PluginSeatsAction {
    data class Observe(val value: Boolean) : PluginSeatsAction

    data object Refresh : PluginSeatsAction
}

fun PluginSeatsState.seats(sessionId: String? = null): List<ClientSlotOccupant> {
    if (!occupancyCurrent || !webShell) return emptyList()
    val slots =
        setOf(
            "settings.section",
            "settings.general.item",
            "plugins.bundle.config",
            "plugins.row.config",
            "main",
            "sidebar.right.pane.tab",
            "tool.view.cordis",
            "conversation.view",
        )
    return occupants.filter {
        !it.official &&
            it.slot in slots &&
            (sessionId != null ||
                it.slot !in
                    setOf("sidebar.right.pane.tab", "tool.view.cordis", "conversation.view"))
    }
}

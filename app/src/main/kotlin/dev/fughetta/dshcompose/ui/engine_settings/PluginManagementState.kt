package dev.fughetta.dshcompose.ui.engine_settings

import dev.fughetta.dshcompose.domain.plugins.CordisPlugin
import dev.fughetta.dshcompose.domain.plugins.PluginBundle
import dev.fughetta.dshcompose.domain.plugins.PluginEntry
import dev.fughetta.dshcompose.domain.plugins.PluginInspection
import dev.fughetta.dshcompose.domain.plugins.PluginMarketEntry
import dev.fughetta.dshcompose.domain.plugins.PluginMarketIndex
import dev.fughetta.dshcompose.domain.plugins.PluginOperationResult
import kotlinx.collections.immutable.*

data class PluginInstallDraft(
    val spec: String = "",
    val registry: String = "",
    val inspection: PluginInspection? = null,
    val approvedBuilds: PersistentList<String> = persistentListOf(),
)

sealed interface PluginConfirmation {
    data class BundleEnabled(val name: String, val enabled: Boolean) : PluginConfirmation

    data class EntryEnabled(val id: String, val enabled: Boolean) : PluginConfirmation

    data class Remove(val name: String) : PluginConfirmation

    data object Install : PluginConfirmation

    data object Builds : PluginConfirmation

    data class Reload(val name: String) : PluginConfirmation

    data object Restart : PluginConfirmation

    data class Stop(val agent: String, val plugin: String) : PluginConfirmation

    data class Undefine(val agent: String, val plugin: String) : PluginConfirmation
}

data class PluginManagementState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val local: Boolean = false,
    val canInstall: Boolean = false,
    /** False on carriers without the Cordis runner methods (the DSH Remote tunnel). */
    val cordisReadable: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val marketLoading: Boolean = false,
    val marketTab: Boolean = false,
    val bundles: PersistentList<PluginBundle> = persistentListOf(),
    val entries: PersistentList<PluginEntry> = persistentListOf(),
    /** Entry ids the engine reports as configurable (plugin-config capable). */
    val configurableIds: Set<String> = emptySet(),
    val cordisUnavailable: Boolean = false,
    val configUnavailable: Boolean = false,
    val cordis: PersistentList<CordisPlugin> = persistentListOf(),
    val market: PluginMarketIndex? = null,
    val marketRows: PersistentList<PluginMarketEntry> = persistentListOf(),
    val query: String = "",
    val category: String? = null,
    val label: String? = null,
    val sort: String? = null,
    val selectedMarket: String? = null,
    val selectedVersion: String? = null,
    val draft: PluginInstallDraft? = null,
    val confirmation: PluginConfirmation? = null,
    val result: PluginOperationResult? = null,
    val requestId: String? = null,
    val uncertain: Boolean = false,
    val phase: String? = null,
    val output: String = "",
    val error: String? = null,
    val marketError: String? = null,
)

sealed interface PluginManagementAction {
    data class Observe(val value: Boolean) : PluginManagementAction

    data object Refresh : PluginManagementAction

    data class Tab(val market: Boolean) : PluginManagementAction

    data class Query(val value: String) : PluginManagementAction

    data class Category(val value: String?) : PluginManagementAction

    data class Label(val value: String?) : PluginManagementAction

    data class Sort(val value: String?) : PluginManagementAction

    data class SelectMarket(val name: String?) : PluginManagementAction

    data class SelectVersion(val version: String) : PluginManagementAction

    data object NewInstall : PluginManagementAction

    data object InstallSelected : PluginManagementAction

    data class Spec(val value: String) : PluginManagementAction

    data class Registry(val value: String) : PluginManagementAction

    data object Inspect : PluginManagementAction

    data object RequestInstall : PluginManagementAction

    data class Request(val confirmation: PluginConfirmation) : PluginManagementAction

    data object Confirm : PluginManagementAction

    data object Dismiss : PluginManagementAction

    data object CloseDraft : PluginManagementAction

    data object CancelInstall : PluginManagementAction

    data object RecoverInstall : PluginManagementAction
}

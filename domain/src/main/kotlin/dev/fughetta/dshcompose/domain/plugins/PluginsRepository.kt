package dev.fughetta.dshcompose.domain.plugins

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import kotlinx.collections.immutable.PersistentList

/**
 * Host plugin inventory, installation, configuration discovery and runtime lifecycle. These
 * operations require neither a client UI manifest nor an active client renderer.
 */
interface PluginsRepository : AutoCloseable {
    val generation: ConnectionGeneration?

    suspend fun inventory(): PluginInventory

    suspend fun market(): Result<PluginMarketIndex>

    fun notice(event: ToolPayload.Obj): PluginManagerNotice?

    val canReadCordis: Boolean

    val canInstallPlugins: Boolean

    val canReadCompletePluginConfig: Boolean

    suspend fun bundles(): List<PluginBundle>

    suspend fun plugins(): PersistentList<PluginEntry>

    suspend fun config(): PersistentList<PluginConfigEntrySummary>

    suspend fun cordis(): PersistentList<CordisPlugin>

    suspend fun inspect(spec: String, registry: String? = null): PluginInspection

    suspend fun setBundleEnabled(name: String, enabled: Boolean): PluginOperationResult

    suspend fun setPluginEnabled(entryId: String, enabled: Boolean): PluginOperationResult

    suspend fun removeBundle(name: String): PluginOperationResult

    suspend fun stopCordis(plugin: CordisPlugin): CordisStopOutcome

    suspend fun removeCordis(plugin: CordisPlugin): Boolean

    suspend fun install(
        spec: String,
        requestId: String,
        registry: String?,
        approvedBuilds: List<String> = emptyList(),
    ): PluginOperationResult

    suspend fun waitForInstall(requestId: String): PluginOperationResult?

    suspend fun cancelInstall(requestId: String): PluginCancelStatus
}

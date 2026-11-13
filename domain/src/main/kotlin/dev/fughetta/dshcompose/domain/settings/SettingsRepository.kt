package dev.fughetta.dshcompose.domain.settings

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.providers.CredentialStatus
import dev.fughetta.dshcompose.domain.providers.ProviderKey
import dev.fughetta.dshcompose.domain.sessions.DrawerPreferences
import dev.fughetta.dshcompose.domain.uiconfig.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode {
    System,
    Light,
    Dark,
}

data class Settings(
    val theme: ThemeMode,
    val dynamicColor: Boolean,
    val autoReviewConfirmed: Boolean,
    val foldCompletedTurns: Boolean,
)

/**
 * Device preferences plus the selected host's settings document. The local streams are always
 * available; [connection] publishes a distinct repository bound to the selected host for document
 * calls.
 */
interface SettingsRepository {
    fun rememberSession(host: String, id: String?)

    fun forgetSession(host: String, id: String)

    fun restoreSession(host: String, known: Set<String>): String?

    val uiConfigUnresolved: Boolean

    fun resetUiConfigStatus()

    suspend fun restoreUiConfig(host: String): String?

    suspend fun rememberUiConfig(host: String, text: String?)

    suspend fun followUiConfig(
        local: Boolean,
        client: UiConfigPorts?,
        kicks: kotlinx.coroutines.flow.Flow<Unit>,
        onRead: suspend (UiConfigRead, String?) -> Unit,
    )

    fun writeUiConfigStatus(directory: String, body: ToolPayload.Obj?)

    val connection: StateFlow<SettingsRepository?>

    fun localPreferences(name: String): PreferenceGroup

    val data: Flow<Settings>

    suspend fun theme(value: ThemeMode)

    suspend fun dynamicColor(value: Boolean)

    suspend fun confirmAutoReview()

    suspend fun foldCompletedTurns(value: Boolean)

    val shell: Flow<ShellPreferences>

    suspend fun quickPanelSwipe(enabled: Boolean)

    suspend fun quickPanelItems(items: List<String>)

    suspend fun silenceRisk(id: String)

    suspend fun rememberSearch(query: String)

    suspend fun clearSearches()

    suspend fun backgroundGuideSeen()

    fun watchDrawer(host: String): Flow<DrawerPreferences>

    suspend fun updateDrawer(host: String, transform: (DrawerPreferences) -> DrawerPreferences)

    val canReadCompletePluginConfig: Boolean

    suspend fun inventory(
        plugins: dev.fughetta.dshcompose.domain.plugins.PluginsRepository,
    ): EngineSettingsInventory

    suspend fun describe(): SettingsDocument

    suspend fun mutate(previous: SettingsNamespace, ops: List<SettingsOp>): SettingsNamespace

    suspend fun credentials(refs: Collection<String>): Map<String, CredentialStatus>

    suspend fun setProviderKey(ref: String, key: ProviderKey)

    suspend fun clearProviderKey(ref: String)

    suspend fun readPluginConfig(entryId: String): PluginConfigDocument

    suspend fun writeNamespaceSecret(
        previous: SettingsNamespace,
        path: List<String>,
        key: ProviderKey?,
    ): SettingsNamespace

    suspend fun writePluginConfig(
        previous: PluginConfigDocument,
        ops: List<SettingsOp>,
    ): PluginConfigDocument
}

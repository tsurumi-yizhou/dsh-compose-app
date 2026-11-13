package dev.fughetta.dshcompose.testing

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.providers.CredentialStatus
import dev.fughetta.dshcompose.domain.providers.ProviderKey
import dev.fughetta.dshcompose.domain.sessions.DrawerPreferences
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.uiconfig.*
import kotlinx.coroutines.flow.*

/**
 * Contract implementation for UI tests: local preferences and the drawer are overridable, the host
 * settings document is not exercised.
 */
internal open class FakeSettingsRepository : SettingsRepository {
    override fun rememberSession(host: String, id: String?) = error("unused")

    override fun forgetSession(host: String, id: String) = error("unused")

    override fun restoreSession(host: String, known: Set<String>): String? = error("unused")

    override val uiConfigUnresolved = false

    override fun resetUiConfigStatus(): Unit = error("unused")

    override suspend fun restoreUiConfig(host: String): String? = error("unused")

    override suspend fun rememberUiConfig(host: String, text: String?): Unit = error("unused")

    override suspend fun followUiConfig(
        local: Boolean,
        client: UiConfigPorts?,
        kicks: kotlinx.coroutines.flow.Flow<Unit>,
        onRead: suspend (UiConfigRead, String?) -> Unit,
    ): Unit = error("unused")

    override fun writeUiConfigStatus(directory: String, body: ToolPayload.Obj?): Unit =
        error("unused")

    override fun localPreferences(
        name: String,
    ): dev.fughetta.dshcompose.domain.settings.PreferenceGroup = error("Unused local preferences")

    override val connection = MutableStateFlow<SettingsRepository?>(null)

    override val data = MutableStateFlow(Settings(ThemeMode.System, true, false, true))

    override suspend fun theme(value: ThemeMode) {
        data.value = data.value.copy(theme = value)
    }

    override suspend fun dynamicColor(value: Boolean) {
        data.value = data.value.copy(dynamicColor = value)
    }

    override suspend fun confirmAutoReview() {
        data.value = data.value.copy(autoReviewConfirmed = true)
    }

    override suspend fun foldCompletedTurns(value: Boolean) {
        data.value = data.value.copy(foldCompletedTurns = value)
    }

    private val shellValues = MutableStateFlow(ShellPreferences())

    override val shell: Flow<ShellPreferences> = shellValues

    override suspend fun quickPanelSwipe(enabled: Boolean) {
        shellValues.value = shellValues.value.copy(quickPanelSwipe = enabled)
    }

    override suspend fun quickPanelItems(items: List<String>) {
        shellValues.value = shellValues.value.copy(quickPanelItems = items)
    }

    override suspend fun silenceRisk(id: String) {}

    override suspend fun rememberSearch(query: String) {}

    override suspend fun clearSearches() {}

    override suspend fun backgroundGuideSeen() {}

    override fun watchDrawer(host: String): Flow<DrawerPreferences> = flowOf(DrawerPreferences())

    override suspend fun updateDrawer(
        host: String,
        transform: (DrawerPreferences) -> DrawerPreferences,
    ) {}

    override val canReadCompletePluginConfig = false

    override suspend fun inventory(
        plugins: dev.fughetta.dshcompose.domain.plugins.PluginsRepository,
    ): dev.fughetta.dshcompose.domain.settings.EngineSettingsInventory =
        dev.fughetta.dshcompose.domain.settings.EngineSettingsInventory(
            describe(),
            plugins.bundles(),
            Result.success(plugins.config()),
        )

    override suspend fun describe(): SettingsDocument = error("unused")

    override suspend fun mutate(
        previous: SettingsNamespace,
        ops: List<SettingsOp>,
    ): SettingsNamespace = error("unused")

    override suspend fun credentials(refs: Collection<String>): Map<String, CredentialStatus> =
        error("unused")

    override suspend fun setProviderKey(ref: String, key: ProviderKey) = error("unused")

    override suspend fun clearProviderKey(ref: String) = error("unused")

    override suspend fun readPluginConfig(entryId: String): PluginConfigDocument = error("unused")

    override suspend fun writeNamespaceSecret(
        previous: SettingsNamespace,
        path: List<String>,
        key: ProviderKey?,
    ): SettingsNamespace = error("unused")

    override suspend fun writePluginConfig(
        previous: PluginConfigDocument,
        ops: List<SettingsOp>,
    ): PluginConfigDocument = error("unused")
}

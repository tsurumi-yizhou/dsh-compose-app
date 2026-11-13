package dev.fughetta.dshcompose.data.settings

import android.content.Context
import dev.fughetta.dshcompose.data.domainOperation
import dev.fughetta.dshcompose.data.uiconfig.UiConfigDataSource
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.providers.CredentialStatus
import dev.fughetta.dshcompose.domain.providers.ProviderKey
import dev.fughetta.dshcompose.domain.sessions.DrawerPreferences
import dev.fughetta.dshcompose.domain.sessions.DrawerPreferencesException
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.uiconfig.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.protocol.SettingsOp as WireSettingsOp
import java.io.File
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

fun createSettingsRepository(
    context: Context,
    applicationScope: CoroutineScope,
    uiConfig: UiConfigDataSource? = null,
): AppSettingsRepository {
    val app = context.applicationContext ?: context
    return AppSettingsRepository(
        SettingsDataSource(app.settingsData),
        ShellPreferencesDataSource(app.localPreferences("dsh_native_shell")),
        DrawerPreferencesDataSource(app.drawerPreferencesData),
        applicationScope,
        LocalPreferences.get(app),
        uiConfig = uiConfig,
    )
}

/**
 * Device preferences (DataStore) plus the selected host's settings document. Local streams never
 * wait for a connection; document calls use the client installed by [connect].
 */
class AppSettingsRepository
internal constructor(
    private val settingsSource: SettingsDataSource? = null,
    private val shellSource: ShellPreferencesDataSource? = null,
    private val drawerSource: DrawerPreferencesDataSource? = null,
    private val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val localSource: LocalPreferences? = null,
    private val uiConfig: UiConfigDataSource? = null,
) : SettingsRepository {
    private fun uiConfiguration(): UiConfigDataSource = checkNotNull(uiConfig)

    override val uiConfigUnresolved: Boolean
        get() = uiConfiguration().unresolved

    override fun resetUiConfigStatus() = uiConfiguration().resetStatus()

    override suspend fun restoreUiConfig(host: String): String? = uiConfiguration().restore(host)

    override suspend fun rememberUiConfig(host: String, text: String?) =
        uiConfiguration().remember(host, text)

    override suspend fun followUiConfig(
        local: Boolean,
        client: UiConfigPorts?,
        kicks: kotlinx.coroutines.flow.Flow<Unit>,
        onRead: suspend (UiConfigRead, String?) -> Unit,
    ) {
        coroutineScope {
            val source = uiConfiguration()
            val pending = Channel<Unit>(Channel.CONFLATED)
            val relay = launch { kicks.collect { pending.send(it) } }
            val receive: suspend (UiConfigRead, File?) -> Unit = { value, directory ->
                onRead(value, directory?.absolutePath)
            }
            try {
                if (local) source.followLocal(pending, receive)
                else source.followRemote(checkNotNull(client), pending, receive)
            } finally {
                relay.cancel()
                pending.close()
            }
        }
    }

    override fun writeUiConfigStatus(directory: String, body: ToolPayload.Obj?) =
        uiConfiguration().writeStatus(File(directory), body?.toJsonElement() as? JsonObject)

    private val selectedSession by lazy {
        SessionSelectionStore(localPreferences("host_selection"))
    }

    override fun rememberSession(host: String, id: String?) = selectedSession.select(host, id)

    override fun forgetSession(host: String, id: String) = selectedSession.forget(host, id)

    override fun restoreSession(host: String, known: Set<String>): String? =
        selectedSession.restore(host, known)

    override fun localPreferences(name: String): PreferenceGroup =
        (localSource ?: error("Local preferences source is missing")).group(name)

    private fun localSettings(): SettingsDataSource =
        settingsSource ?: error("Settings source is missing")

    private fun shellPreferences(): ShellPreferencesDataSource =
        shellSource ?: error("Shell preferences source is missing")

    private fun drawerPreferences(): DrawerPreferencesDataSource =
        drawerSource ?: error("Drawer preferences source is missing")

    private val mutableConnection = MutableStateFlow<SettingsRepository?>(null)
    override val connection = mutableConnection.asStateFlow()
    @Volatile private var client: ProtocolClient? = null
    @Volatile private var selected: AppSettingsRepository? = null
    private var generation: ConnectionGeneration? = null

    fun connect(client: ProtocolClient?) {
        val generation = client?.mux?.state?.value?.generation
        if (selected?.client === client && selected?.generation == generation) return
        disconnect()
        if (client == null) return
        val next =
            AppSettingsRepository(
                settingsSource,
                shellSource,
                drawerSource,
                applicationScope,
                localSource,
                uiConfig = uiConfig,
            )
        next.client = client
        next.generation = generation
        next.mutableConnection.value = next
        selected = next
        mutableConnection.value = next
    }

    fun disconnect() {
        selected?.let {
            it.client = null
            it.mutableConnection.value = null
        }
        selected = null
        client = null
        mutableConnection.value = null
    }

    private suspend fun <T> request(action: suspend ProtocolClient.() -> T): T {
        val owner = selected ?: this
        val client = owner.client ?: throw CancellationException("Settings connection retired")
        val generation = owner.generation
        if (client.mux.state.value.generation != generation)
            throw CancellationException("Settings connection retired")
        return domainOperation {
            client.action().also {
                if (owner.client !== client || client.mux.state.value.generation != generation)
                    throw CancellationException("Settings connection retired")
            }
        }
    }

    override val data
        get() =
            localSettings().data.map { preferences ->
                Settings(
                    theme =
                        ThemeMode.entries.firstOrNull { it.name == preferences[SettingsKeys.theme] }
                            ?: ThemeMode.System,
                    dynamicColor = preferences[SettingsKeys.dynamicColor] ?: true,
                    autoReviewConfirmed = preferences[SettingsKeys.autoReviewConfirmed] ?: false,
                    foldCompletedTurns = preferences[SettingsKeys.foldCompletedTurns] ?: true,
                )
            }

    override suspend fun theme(value: ThemeMode) =
        localSettings().write(SettingsKeys.theme, value.name)

    override suspend fun dynamicColor(value: Boolean) =
        localSettings().write(SettingsKeys.dynamicColor, value)

    override suspend fun confirmAutoReview() =
        localSettings().write(SettingsKeys.autoReviewConfirmed, true)

    override suspend fun foldCompletedTurns(value: Boolean) =
        localSettings().write(SettingsKeys.foldCompletedTurns, value)

    override val shell
        get() =
            shellPreferences().data.map { values ->
                ShellPreferences(
                    quickPanelSwipe = values["quick_panel_swipe"] as? Boolean ?: true,
                    quickPanelItems =
                        (values["quick_panel_items"] as? String)?.split(',')?.distinct()
                            ?: DefaultQuickPanelItems,
                    silencedRisks =
                        (values["risk_notice_silenced"] as? Set<*>)
                            ?.filterIsInstance<String>()
                            ?.toSet()
                            .orEmpty(),
                    recentSearches =
                        (values["settings_search_recent"] as? String)
                            ?.split('\n')
                            ?.filter(String::isNotBlank)
                            ?.take(ShellPreferences.RecentLimit)
                            .orEmpty(),
                    backgroundGuideSeen =
                        values["phone_background_guide_seen_v1"] as? Boolean ?: false,
                )
            }

    override suspend fun quickPanelSwipe(enabled: Boolean) =
        shellPreferences().edit { it.putBoolean("quick_panel_swipe", enabled) }

    override suspend fun quickPanelItems(items: List<String>) =
        shellPreferences().edit {
            it.putString("quick_panel_items", items.distinct().joinToString(","))
        }

    override suspend fun silenceRisk(id: String) =
        shellPreferences().edit {
            it.putStringSet(
                "risk_notice_silenced",
                getStringSet("risk_notice_silenced", null).orEmpty() + id,
            )
        }

    override suspend fun rememberSearch(query: String) {
        val text = query.trim().replace('\n', ' ')
        if (text.isEmpty()) return
        shellPreferences().edit { edit ->
            val recent = getString("settings_search_recent", null)?.split('\n').orEmpty()
            edit.putString(
                "settings_search_recent",
                (listOf(text) + recent.filter { it != text })
                    .take(ShellPreferences.RecentLimit)
                    .joinToString("\n"),
            )
        }
    }

    override suspend fun clearSearches() =
        shellPreferences().edit { it.remove("settings_search_recent") }

    override suspend fun backgroundGuideSeen() =
        shellPreferences().edit { it.putBoolean("phone_background_guide_seen_v1", true) }

    override fun watchDrawer(host: String): Flow<DrawerPreferences> =
        drawerPreferences().watch(host).catch { error ->
            if (error is CancellationException) throw error
            throw DrawerPreferencesException("drawer/preferences-read-failed", error)
        }

    override suspend fun updateDrawer(
        host: String,
        transform: (DrawerPreferences) -> DrawerPreferences,
    ) {
        applicationScope
            .async {
                try {
                    drawerPreferences().update(host, transform)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw DrawerPreferencesException("drawer/preferences-write-failed", error)
                }
            }
            .await()
    }

    override val canReadCompletePluginConfig: Boolean
        get() = (selected ?: this).client?.canReadCompletePluginConfig == true

    override suspend fun inventory(
        plugins: dev.fughetta.dshcompose.domain.plugins.PluginsRepository,
    ): EngineSettingsInventory = readEngineSettings(EngineSettingsPorts(this, plugins))

    override suspend fun describe(): SettingsDocument = request { describeSettings().toDomain() }

    override suspend fun mutate(
        previous: SettingsNamespace,
        ops: List<SettingsOp>,
    ): SettingsNamespace = request {
        mutateSettingsView(previous.toWire(), ops.map(::settingsOp)).toDomain()
    }

    override suspend fun credentials(refs: Collection<String>): Map<String, CredentialStatus> =
        request {
            describeCredentials(refs)
        }

    override suspend fun setProviderKey(ref: String, key: ProviderKey) = request {
        setProviderKey(ref, key)
    }

    override suspend fun clearProviderKey(ref: String) = request { clearProviderKey(ref) }

    override suspend fun writeNamespaceSecret(
        previous: SettingsNamespace,
        path: List<String>,
        key: ProviderKey?,
    ): SettingsNamespace = request { writeNamespaceSecret(previous.toWire(), path, key).toDomain() }

    override suspend fun readPluginConfig(entryId: String): PluginConfigDocument = request {
        readPluginConfig(entryId).toDomain()
    }

    override suspend fun writePluginConfig(
        previous: PluginConfigDocument,
        ops: List<SettingsOp>,
    ): PluginConfigDocument = request {
        writePluginConfig(previous.toWire(), ops.map(::settingsOp)).toDomain()
    }

    private fun settingsOp(op: SettingsOp) =
        WireSettingsOp(op.path, op.value?.toJsonElement(), op.unset)

    private fun SettingsDescription.toDomain() =
        SettingsDocument(
            namespaces.map { it.toDomain() },
            writable,
            hasDocument,
        )

    private fun SettingsNamespaceView.toDomain() =
        SettingsNamespace(
            ns,
            revision,
            writable,
            autoGenerate,
            secrets.toList(),
            (value as JsonElement).toToolPayload().asObjectValue(),
            base?.toToolPayload(),
            user?.toToolPayload(),
            schema,
        )

    private fun PluginConfigEntryDetail.toDomain() =
        PluginConfigDocument(
            entryId,
            revision,
            writable,
            secrets.toList(),
            (value as JsonElement).toToolPayload().asObjectValue(),
            base?.toToolPayload(),
            user?.toToolPayload(),
            schema,
        )

    private fun SettingsNamespace.toWire() =
        SettingsNamespaceView(
            ns,
            schema,
            value?.toJsonElement() as? JsonObject ?: JsonObject(emptyMap()),
            revision,
            base?.toJsonElement() as? JsonObject,
            user?.toJsonElement() as? JsonObject,
            autoGenerate,
            secrets.toPersistentList(),
            writable,
        )

    private fun PluginConfigDocument.toWire() =
        PluginConfigEntryDetail(
            entryId,
            revision,
            schema,
            value?.toJsonElement() as? JsonObject ?: JsonObject(emptyMap()),
            base?.toJsonElement() as? JsonObject,
            user?.toJsonElement() as? JsonObject,
            secrets.toPersistentList(),
            writable,
        )

    private fun ToolPayload.asObjectValue(): ToolPayload.Obj? = this as? ToolPayload.Obj
}

package dev.fughetta.dshcompose.nativeplugins

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.features.HostModule
import dev.fughetta.dshcompose.domain.features.ModuleRegistry
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.plugins.PluginUiRepository
import dev.fughetta.dshcompose.nativefiles.*
import dev.fughetta.dshcompose.nativemedia.*
import dev.fughetta.dshcompose.sessions.foreground
import dev.fughetta.dshcompose.ui.AppModuleContribution
import dev.fughetta.dshcompose.ui.nativeplugins.*
import dev.fughetta.dshcompose.ui.pluginModuleId
import dev.fughetta.dshcompose.ui.toUiJson
import dev.fughetta.dshcompose.ui.toUiPayload
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * Owns only the selected host and visible conversation. All state and platform calls run on Main.
 */
@OptIn(FlowPreview::class)
class NativePluginsViewModel(
    internal val context: Context,
    parent: CoroutineScope? = null,
    private val appVersion: String,
    private val moduleRegistry: ModuleRegistry<AppModuleContribution>? = null,
) : ViewModel() {
    private val lifetime = parent ?: viewModelScope
    private var closed = false

    override fun onCleared() {
        close()
    }

    internal val scope =
        CoroutineScope(lifetime.coroutineContext + SupervisorJob(lifetime.coroutineContext[Job]))
    private val modules = moduleRegistry ?: ModuleRegistry<AppModuleContribution>(scope)
    private var definitions = emptyMap<String, HostModule<AppModuleContribution>>()
    internal val mutable = MutableStateFlow(NativePluginsState())
    val state = mutable.asStateFlow()
    internal val outgoing = MutableSharedFlow<NativePluginDispatch>(extraBufferCapacity = 16)
    val effects = outgoing.asSharedFlow()
    private val preferences = context.localPreferences("native_plugins")
    internal var hostKey = ""
    internal var locale = "en"
    private var source: PluginUiRepository? = null
    internal var api: PluginUiRepository? = null
    internal var stream: PluginUiRepository? = null
    internal var caps = NativePluginCaps(appVersion, locale, nativePluginComponents)
    private var contributions: NativePluginContributions? = null
    private var hostJob: Job? = null
    private var panelJob: Job? = null
    private var panelInvokeJob: Job? = null
    private var panelQueueJob: Job? = null
    private val panelQueue = linkedMapOf<String, NativePluginViewAction>()
    private var stripsJob: Job? = null
    private var stripsFeeds = mutableListOf<NativePluginFeed>()
    private var notesJob: Job? = null
    private val noteFeeds = mutableListOf<NativePluginFeed>()
    private var visibleTurnIds: List<String> = emptyList()
    private var noteKeys: List<Triple<String, String, String?>> = emptyList()
    private val noteData = mutableMapOf<String, JsonObject>()
    internal var sessionId: String? = null
    internal var foreground = true
    internal var generation = 0
    private var consent: CompletableDeferred<Boolean>? = null
    internal var launchFiles: ((Intent) -> Unit)? = null
    internal val files = NativeFiles(context) { foreground }
    internal val media = NativeMedia(context, this::mediaEvent)

    internal data class Owner(
        val pkg: String,
        val instance: String,
        val key: MediaLease,
        val session: String?,
        val connection: String,
        val context: NativePluginScope,
        val roles: MutableSet<String> = mutableSetOf(),
        val ended: MutableSet<String> = mutableSetOf(),
        var seq: Int = 0,
    )

    internal var owner: Owner? = null
    internal var mediaFeed: NativePluginFeed? = null
    internal var mediaJob: Job? = null
    internal var audioFile: File? = null
    internal var mediaFailed = false
    internal val toasts = mutableMapOf<String, Long>()

    fun attach(
        activity: ComponentActivity?,
        fileLauncher: ((Intent) -> Unit)? = null,
    ) {
        media.attach(activity)
        launchFiles = fileLauncher
        if (activity == null) {
            files.detach()
            revoke()
        }
    }

    fun acceptFileResult(result: ActivityResult) = files.acceptResult(result)

    fun connect(connection: PluginUiRepository?, hostKey: String, locale: String) {
        if (source === connection && this.hostKey == hostKey && this.locale == locale) return
        generation++
        modules.publish("compose", emptyList())
        definitions = emptyMap()
        hostJob?.cancel()
        panelJob?.cancel()
        closeSurfaceFeeds()
        revoke()
        files.detach()
        stream?.close()
        stream = null
        api = null
        source = connection
        contributions = null
        consent?.complete(false)
        consent = null
        this.hostKey = hostKey
        this.locale = locale
        mutable.value =
            NativePluginsState(
                disabled = preferences.getStringSet("off:$hostKey", emptySet()).orEmpty().toSet(),
            )
        val next = connection ?: return
        caps =
            NativePluginCaps(
                appVersion,
                locale,
                nativePluginComponents,
                (if (mediaFailed) emptySet() else media.features) + setOf("micro", "files"),
            )
        val socket = next.openNative(caps, scope)
        api = socket
        stream = socket
        socket.foreground(foreground)
        mutable.update { it.copy(reachable = true) }
        hostJob = scope.launch {
            launch { socket.changes.collect { readContributions() } }
            launch {
                socket.link.collect { if (it != NativePluginLink.Open && owner != null) revoke() }
            }
            readContributions()
        }
    }

    fun session(id: String?) {
        if (sessionId == id) return
        sessionId = id
        consent?.complete(false)
        closePanel()
        revoke()
        files.detach()
        refreshStrips()
        visibleTurns(emptyList())
    }

    fun foreground(value: Boolean) {
        foreground = value
        stream?.foreground(value)
        if (!value) {
            consent?.complete(false)
            revoke()
        } else refresh()
    }

    fun refresh() {
        scope.launch { readContributions() }
    }

    private suspend fun readContributions() {
        val current = api ?: return
        val epoch = generation
        try {
            val read = current.contributions(caps, contributions)
            if (epoch != generation) return
            if (read == contributions) return
            contributions = read
            refreshModules(read?.plugins.orEmpty())
            val owned = owner
            if (
                owned != null &&
                    read?.plugins?.none {
                        it.pkg == owned.pkg && it.capabilities.containsAll(owned.roles)
                    } != false
            )
                revoke()
            val panel = state.value.panel
            if (
                panel != null &&
                    read
                        ?.plugins
                        ?.flatMap { it.panels }
                        ?.none { it.pkg == panel.panel.pkg && it.id == panel.panel.id } != false
            )
                closePanel()
            refreshStrips()
            visibleTurns(visibleTurnIds)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (epoch == generation) mutable.update { it.copy(error = code(error)) }
        }
    }

    private fun pluginStates() =
        modules.state.value.states
            .filterKeys { it.startsWith("plugin:") }
            .mapKeys { it.key.removePrefix("plugin:") }

    private fun refreshModules(plugins: List<NativePlugin>) {
        definitions = plugins.associate { plugin ->
            val previous = definitions[plugin.pkg]
            plugin.pkg to
                (previous?.takeIf {
                    (it.contributions.first() as AppModuleContribution.Extension).plugin == plugin
                }
                    ?: HostModule<AppModuleContribution>(
                        pluginModuleId(plugin.pkg),
                        plugin.dependencies.map(::pluginModuleId).toSet(),
                        dev.fughetta.dshcompose.ui.nativeModuleContributions(plugin),
                    ) { lifetime ->
                        lifetime.onDispose {
                            if (state.value.consent?.pkg == plugin.pkg) consent?.complete(false)
                            if (state.value.panel?.panel?.pkg == plugin.pkg) closePanel()
                            if (owner?.pkg == plugin.pkg) revoke()
                        }
                    })
        }
        val disabled =
            state.value.disabled
                .filter { it.endsWith("|module") }
                .map { it.removeSuffix("|module") }
                .toSet()
        modules.publish(
            "compose",
            definitions.values.toList(),
            disabled.filter { it in definitions }.map(::pluginModuleId).toSet(),
        )
        mutable.update { it.copy(plugins = plugins, modules = pluginStates(), error = null) }
    }

    fun setSurface(pkg: String, surface: String, enabled: Boolean) {
        val key = "$pkg|$surface"
        mutable.update { it.copy(disabled = if (enabled) it.disabled - key else it.disabled + key) }
        preferences.edit().putStringSet("off:$hostKey", state.value.disabled).apply()
        if (surface == "module") refreshModules(state.value.plugins)
        refreshStrips()
        visibleTurns(visibleTurnIds)
        state.value.panel
            ?.takeIf { it.panel.pkg == pkg && surface == "panels" && !enabled }
            ?.let { closePanel() }
    }

    fun answerConsent(allowed: Boolean) {
        consent?.complete(allowed)
    }

    suspend fun asset(pkg: String, path: String): ByteArray? {
        val epoch = generation
        val bytes = api?.asset(pkg, path) ?: return null
        return bytes.takeIf { generation == epoch }
    }

    internal suspend fun consent(
        plugin: NativePlugin,
        wants: Set<String> = emptySet(),
        url: String? = null,
    ): Boolean {
        val key = "ack:$hostKey:${plugin.pkg}:${wants.sorted().joinToString("+")}"
        if (url == null && preferences.getBoolean(key, false)) return true
        if (consent != null) return false
        val result = CompletableDeferred<Boolean>()
        consent = result
        mutable.update {
            it.copy(consent = NativePluginConsent(plugin.pkg, plugin.title, wants, url))
        }
        return try {
            val accepted = result.await()
            if (accepted && url == null) preferences.edit().putBoolean(key, true).apply()
            accepted
        } finally {
            if (consent === result) {
                consent = null
                mutable.update { it.copy(consent = null) }
            }
        }
    }

    fun invoke(
        action: NativePluginAction,
        scope: NativePluginScope = NativePluginScope(sessionId),
        draft: String? = null,
        message: String? = null,
    ) {
        val plugin = state.value.plugins.find { it.pkg == action.pkg } ?: return
        if (!state.value.enabled(action.pkg, action.surface)) return
        runInvoke(plugin, action.id, action.surface, scope, null, draft, message, action.effects)
    }

    fun panelAction(action: NativePluginViewAction) {
        val panel = state.value.panel ?: return
        val plugin = state.value.plugins.find { it.pkg == panel.panel.pkg } ?: return
        val key =
            if (action.gesture) UUID.randomUUID().toString()
            else "${action.id}|${action.args.viewString("bind")}"
        if (panelQueue.size >= 32 && key !in panelQueue) {
            issue(plugin.pkg, "action-queue-full")
            return
        }
        panelQueue[key] = action
        if (panelQueueJob?.isActive == true) return
        panelQueueJob =
            (modules.scope(pluginModuleId(plugin.pkg)) ?: return).launch {
                while (panelQueue.isNotEmpty()) {
                    val next = panelQueue.entries.first()
                    panelQueue.remove(next.key)
                    runInvoke(
                            plugin,
                            panel.panel.id,
                            "panel",
                            panel.scope,
                            next.value,
                            null,
                            null,
                            emptyList(),
                        )
                        ?.join()
                }
            }
    }

    private fun runInvoke(
        plugin: NativePlugin,
        id: String,
        surface: String,
        context: NativePluginScope,
        action: NativePluginViewAction?,
        draft: String?,
        message: String?,
        static: List<ToolPayload.Obj>,
    ): Job? {
        val bridge = api ?: return null
        val epoch = generation
        val originApi = api ?: return null
        val originMux = originApi.generation
        val execution = modules.scope(pluginModuleId(plugin.pkg)) ?: return null
        val key = "${plugin.pkg}|$surface|$id"
        if (key in state.value.busy) return null
        mutable.update {
            it.copy(busy = it.busy + key, panel = it.panel?.copy(busy = surface == "panel"))
        }
        val job = execution.launch {
            val bindings =
                (action?.args?.get("values") as? JsonObject)?.keys.orEmpty() +
                    listOfNotNull(action?.args?.viewString("bind"))
            try {
                val wants =
                    plugin.wants
                        .filter {
                            (it == "draft" || it == "clipboard") &&
                                surface.startsWith("composer.") ||
                                it == "message" && surface == "message.actions"
                        }
                        .toSet()
                if (wants.isNotEmpty() && !consent(plugin, wants)) return@launch
                if (epoch != generation || context.sessionId != sessionId) return@launch
                // Capture after consent for menu actions; queued changes keep the original gesture
                // time.
                val tapped = action?.atNanos ?: System.nanoTime()
                val userGesture = action?.gesture ?: true
                bridge.invokeNative(
                    static,
                    request = { conn ->
                        val details = buildJsonObject {
                            if ("draft" in wants && draft != null) put("draft", draft.take(8000))
                            if ("message" in wants && message != null)
                                put("message", message.take(8000))
                            if ("clipboard" in wants) {
                                val manager =
                                    this@NativePluginsViewModel.context.getSystemService(
                                        ClipboardManager::class.java,
                                    )
                                manager.primaryClip
                                    ?.getItemAt(0)
                                    ?.coerceToText(this@NativePluginsViewModel.context)
                                    ?.toString()
                                    ?.let { put("clipboard", it.take(8000)) }
                            }
                        }
                        NativePluginInvocation(
                            plugin.pkg,
                            id,
                            surface,
                            context,
                            action?.id,
                            action?.args?.toUiPayload(),
                            details.toUiPayload() as ToolPayload.Obj,
                            locale,
                            conn,
                            userGesture,
                        )
                    },
                ) { conn, result ->
                    if (epoch != generation || context.sessionId != sessionId) return@invokeNative
                    if (
                        surface == "panel" &&
                            state.value.panel?.panel?.let { it.pkg == plugin.pkg && it.id == id } !=
                                true
                    )
                        return@invokeNative
                    applyEffects(
                        plugin,
                        result.effects.map { it.toUiJson() as JsonObject },
                        id,
                        context,
                        conn,
                        tapped,
                        userGesture,
                        static.isEmpty(),
                        originApi,
                        originMux,
                    )
                    if (surface == "panel")
                        mutable.update {
                            it.copy(
                                panel =
                                    it.panel?.let { panel ->
                                        panel.copy(
                                            actionRevision = panel.actionRevision + 1,
                                            accepted = bindings.toSet(),
                                            rejected = emptySet(),
                                        )
                                    },
                            )
                        }
                    result.issues.forEach { issue(plugin.pkg, it) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation) {
                    issue(plugin.pkg, code(error))
                    if (surface == "panel")
                        mutable.update {
                            it.copy(
                                panel =
                                    it.panel?.let { panel ->
                                        panel.copy(
                                            actionRevision = panel.actionRevision + 1,
                                            rejected = bindings.toSet(),
                                            accepted = emptySet(),
                                            error = code(error),
                                        )
                                    },
                            )
                        }
                }
            } finally {
                if (epoch == generation)
                    mutable.update {
                        it.copy(busy = it.busy - key, panel = it.panel?.copy(busy = false))
                    }
            }
        }
        if (surface == "panel") panelInvokeJob = job
        return job
    }

    fun openPanel(
        pkg: String,
        id: String,
        context: NativePluginScope = NativePluginScope(sessionId),
    ) {
        val panel =
            state.value.plugins.flatMap { it.panels }.find { it.pkg == pkg && it.id == id }
                ?: return
        if (!state.value.enabled(pkg, "panels")) return
        closePanel()
        mutable.update { it.copy(panel = NativePluginPanelState(panel, context)) }
        loadPanel()
    }

    internal fun loadPanel() {
        panelJob?.cancel()
        val selected = state.value.panel ?: return
        val bridge = api ?: return
        val epoch = generation
        panelJob =
            (modules.scope(pluginModuleId(selected.panel.pkg)) ?: return).launch {
                try {
                    bridge
                        .panel(
                            selected.panel.pkg,
                            selected.panel.id,
                            selected.scope,
                            caps,
                            selected.panel.micro,
                        )
                        .collect { update ->
                            if (epoch != generation || state.value.panel?.panel != selected.panel)
                                return@collect
                            when (update) {
                                is NativePanelUpdate.Snapshot ->
                                    mutable.update {
                                        it.copy(
                                            panel =
                                                it.panel?.copy(
                                                    view = update.value.view.toUiJson(),
                                                    data = update.value.data.toUiJson(),
                                                    loading = false,
                                                    error = null,
                                                ),
                                        )
                                    }
                                is NativePanelUpdate.Patch -> {
                                    if (update.value.stale)
                                        modules.scope(pluginModuleId(selected.panel.pkg))?.launch {
                                            loadPanel()
                                        }
                                    else
                                        mutable.update {
                                            it.copy(
                                                panel =
                                                    it.panel?.copy(
                                                        data = update.value.data.toUiJson(),
                                                        error = update.value.ended,
                                                    ),
                                            )
                                        }
                                }
                                is NativePanelUpdate.Micro ->
                                    mutable.update {
                                        it.copy(panel = it.panel?.copy(micro = update.text))
                                    }
                            }
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (epoch == generation)
                        mutable.update {
                            it.copy(panel = it.panel?.copy(loading = false, error = code(error)))
                        }
                }
            }
    }

    fun closePanel() {
        panelQueueJob?.cancel()
        panelQueueJob = null
        panelQueue.clear()
        panelInvokeJob?.cancel()
        panelInvokeJob = null
        panelJob?.cancel()
        mutable.update { it.copy(panel = null) }
    }

    private fun refreshStrips() {
        stripsJob?.cancel()
        stripsFeeds.forEach { it.close() }
        stripsFeeds.clear()
        mutable.update { it.copy(strips = emptyMap()) }
        val socket = stream ?: return
        stripsJob = scope.launch {
            state.value.plugins
                .flatMap { plugin ->
                    if (state.value.enabled(plugin.pkg, "composer.strip"))
                        plugin.strips.take(2).map { plugin to it }
                    else emptyList()
                }
                .take(8)
                .forEach { (plugin, strip) ->
                    val id = strip.text("id") ?: return@forEach
                    val key = "${plugin.pkg}|$id"
                    mutable.update {
                        it.copy(
                            strips = it.strips + (key to (strip["text"]?.toUiJson() ?: JsonNull)),
                        )
                    }
                    if (strip["live"]?.primitiveContent?.toBooleanStrictOrNull() == true) {
                        val feed = socket.subscribe(plugin.pkg, id, NativePluginScope(sessionId))
                        stripsFeeds.add(feed)
                        launch {
                            feed.state.sample(100).collect { data ->
                                mutable.update {
                                    it.copy(strips = it.strips + (key to data.data.toUiJson()))
                                }
                            }
                        }
                    }
                }
            awaitCancellation()
        }
    }

    /**
     * Supply visible turn ids only; eight feeds maximum irrespective of total conversation length.
     */
    fun visibleTurns(turnIds: List<String>) {
        visibleTurnIds = turnIds.distinct().take(100)
        val socket = stream ?: return
        val pairs =
            if (visibleTurnIds.isEmpty()) emptyList()
            else
                state.value.plugins
                    .filter { state.value.enabled(it.pkg, "turn.note") }
                    .flatMap { plugin ->
                        plugin.turnNotes.map { Triple(plugin.pkg, it, sessionId) }
                    }
                    .take(8)
        if (noteKeys == pairs) {
            projectNotes()
            return
        }
        notesJob?.cancel()
        noteFeeds.forEach { it.close() }
        noteFeeds.clear()
        noteData.clear()
        noteKeys = pairs
        projectNotes()
        if (pairs.isEmpty()) return
        notesJob = scope.launch {
            pairs.forEach { (pkg, id, session) ->
                val feed = socket.subscribe(pkg, id, NativePluginScope(session))
                noteFeeds.add(feed)
                launch {
                    feed.state.sample(100).collect { value ->
                        noteData["$pkg|$id"] =
                            value.data.toUiJson() as? JsonObject ?: JsonObject(emptyMap())
                        projectNotes()
                    }
                }
            }
            awaitCancellation()
        }
    }

    private fun projectNotes() {
        val visible =
            visibleTurnIds
                .associateWith { turn ->
                    noteKeys
                        .mapNotNull { (pkg, id, _) ->
                            (noteData["$pkg|$id"]?.get(turn) as? JsonObject)
                                ?.viewString("text")
                                ?.take(120)
                                ?.let {
                                    dev.fughetta.dshcompose.ui.nativeplugins.NativeTurnNote(
                                        pkg,
                                        it,
                                    )
                                }
                        }
                        .take(2)
                }
                .filterValues { it.isNotEmpty() }
        mutable.update { it.copy(turnNotes = visible) }
    }

    private fun closeSurfaceFeeds() {
        stripsJob?.cancel()
        notesJob?.cancel()
        stripsFeeds.forEach { it.close() }
        noteFeeds.forEach { it.close() }
        stripsFeeds.clear()
        noteFeeds.clear()
        noteKeys = emptyList()
        noteData.clear()
    }

    internal fun issue(pkg: String, value: String) {
        mutable.update {
            it.copy(
                issues =
                    it.issues +
                        (pkg to (listOf(value) + it.issues[pkg].orEmpty()).distinct().take(20)),
            )
        }
    }

    internal fun code(error: Exception): String =
        operationErrorCode(error)
            ?: when (error) {
                is NativeMediaException -> error.code
                is NativeFileException -> error.code
                else -> "dsh-compose/request-failed"
            }

    fun close() {
        if (closed) return
        closed = true
        generation++
        if (moduleRegistry == null) modules.close() else modules.publish("compose", emptyList())
        consent?.complete(false)
        closePanel()
        closeSurfaceFeeds()
        revoke()
        stream?.close()
        files.close()
        media.close()
        scope.cancel()
    }

    companion object {
        internal val pages =
            setOf(
                "session-detail",
                "artifacts",
                "files",
                "terminal",
                "export",
                "jobs",
                "plan-goal",
                "skills",
                "subagents",
                "reference",
                "schedules",
                "permission",
                "agent-presets",
                "models",
                "providers",
                "subagent-settings",
                "agent-loop",
                "shell-settings",
                "web-search",
                "plugins",
                "plugin-inventory",
                "cordis",
                "agent-team",
                "cot-translation",
                "community",
                "ds-bot",
                "share-profile",
                "search",
                "notifications",
                "workspaces",
                "general",
                "account",
                "remote",
                "phone",
                "engine",
                "diagnostics",
                "new-chat",
                "settings",
            )
    }
}

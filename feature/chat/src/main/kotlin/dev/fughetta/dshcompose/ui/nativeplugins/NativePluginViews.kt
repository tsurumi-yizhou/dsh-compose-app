package dev.fughetta.dshcompose.ui.nativeplugins

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.ReadablePane
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings
import dev.fughetta.dshcompose.ui.phone.PerformanceAnimatedVisibility
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun NativePluginActions(
    state: NativePluginsState,
    surface: String,
    onAction: (NativePluginAction) -> Unit,
) {
    val actions = nativePluginActions(state, surface)
    if (actions.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            key(action.pkg, action.id) {
                AssistChip(
                    onClick = { onAction(action) },
                    enabled = "${action.pkg}|$surface|${action.id}" !in state.busy,
                    label = { Text(action.label) },
                )
            }
        }
    }
}

/** The enabled plugins' actions on [surface], capped as the surface allows. */
fun nativePluginActions(state: NativePluginsState, surface: String): List<NativePluginAction> =
    state.plugins
        .flatMap { plugin ->
            plugin.actions.filter { it.surface == surface && state.enabled(plugin.pkg, surface) }
        }
        .take(if (surface in setOf("composer.actions", "composer.tools")) 8 else 32)

@Composable
fun NativePluginPanels(
    state: NativePluginsState,
    placements: Set<String>,
    onPanel: (String, String) -> Unit,
) {
    val panels =
        dev.fughetta.dshcompose.ui.LocalFeaturePages.current
            .mapNotNull { (it.target as? dev.fughetta.dshcompose.ui.PageTarget.Extension)?.panel }
            .filter { panel ->
                state.enabled(panel.pkg, "panels") && panel.placements.any(placements::contains)
            }
    if (panels.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        panels.forEach { panel ->
            key(panel.pkg, panel.id) {
                AssistChip(
                    onClick = { onPanel(panel.pkg, panel.id) },
                    label = { Text(panel.title) },
                )
            }
        }
    }
}

@Composable
fun NativePluginStrips(
    state: NativePluginsState,
    onAction: (NativePluginAction) -> Unit,
    onPanel: (String, String) -> Unit,
) {
    state.plugins
        .filter { state.enabled(it.pkg, "composer.strip") }
        .flatMap { p -> p.strips.map { p to it } }
        .take(8)
        .forEach { (plugin, strip) ->
            val id = strip.text("id") ?: return@forEach
            val data = state.strips["${plugin.pkg}|$id"]
            val text =
                when (data) {
                    is JsonObject -> data.viewString("text")
                    is JsonPrimitive -> data.contentOrNull
                    else -> null
                } ?: strip.text("text") ?: return@forEach
            val panel = strip.text("panel")
            Text(
                text.take(24),
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier.clickable(
                            enabled =
                                panel != null ||
                                    strip["tappable"]?.primitiveContent?.toBooleanStrictOrNull() ==
                                        true,
                        ) {
                            if (panel != null) onPanel(plugin.pkg, panel)
                            else
                                onAction(NativePluginAction(plugin.pkg, id, text, "composer.strip"))
                        }
                        .padding(vertical = 4.dp),
            )
        }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NativePluginPanel(
    state: NativePluginsState,
    onAction: (NativePluginViewAction) -> Unit,
    onClose: () -> Unit,
    loadAsset: suspend (String, String) -> ByteArray? = { _, _ -> null },
) {
    val panel = state.panel ?: return
    val performance = LocalPerformanceSettings.current
    val contentAlpha by
        animateFloatAsState(
            if (panel.view != JsonNull) 1f else 0f,
            animationSpec = if (performance.animations) tween(180) else snap(),
            label = "pluginContent",
        )
    val progressAlpha by
        animateFloatAsState(
            if (panel.loading || panel.busy) 1f else 0f,
            animationSpec = if (performance.animations) tween(140) else snap(),
            label = "pluginProgress",
        )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(panel.panel.title) },
                navigationIcon = { UpButton(onClose) },
            )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            Column(Modifier.fillMaxSize().padding(horizontal = inset, vertical = Spacing.Large)) {
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (progressAlpha > 0f)
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().graphicsLayer { alpha = progressAlpha },
                        )
                }
                PerformanceAnimatedVisibility(panel.error != null) {
                    Text(panel.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                Box(
                    Modifier.weight(1f)
                        .graphicsLayer { alpha = contentAlpha }
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (panel.view != JsonNull) {
                        key(panel.panel.pkg, panel.panel.id, panel.scope) {
                            NativePluginView(
                                panel.view,
                                panel.data,
                                !panel.busy,
                                onAction,
                                loadAsset = { loadAsset(panel.panel.pkg, it) },
                                actionRevision = panel.actionRevision,
                                accepted = panel.accepted,
                                rejected = panel.rejected,
                            )
                        }
                    }
                }
                panel.micro?.let { Text(it.take(240), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
fun NativePluginConsentDialog(state: NativePluginsState, answer: (Boolean) -> Unit) {
    val consent = state.consent ?: return
    val draft = stringResource(R.string.native_plugin_draft)
    val clipboard = stringResource(R.string.native_plugin_clipboard)
    val message = stringResource(R.string.native_plugin_message)
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text(consent.title) },
        text = {
            Text(
                when {
                    consent.url != null ->
                        stringResource(
                            R.string.native_plugin_link_consent,
                            runCatching { java.net.URI(consent.url).host }.getOrNull().orEmpty(),
                        )
                    else ->
                        stringResource(
                            R.string.native_plugin_read_consent,
                            consent.wants.joinToString(", ") { want ->
                                when (want) {
                                    "draft" -> draft
                                    "clipboard" -> clipboard
                                    else -> message
                                }
                            },
                        )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { answer(true) }) {
                Text(stringResource(R.string.native_plugin_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { answer(false) }) {
                Text(stringResource(R.string.native_plugin_cancel))
            }
        },
    )
}

@Composable
fun NativePluginPreferences(
    state: NativePluginsState,
    onSurface: (String, String, Boolean) -> Unit,
    onPanel: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.plugins.isEmpty() && state.error == null)
            Text(
                stringResource(R.string.native_plugins_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        state.plugins.forEach { plugin ->
            Text(plugin.title, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.native_module_enabled), Modifier.weight(1f))
                Switch(
                    checked = "${plugin.pkg}|module" !in state.disabled,
                    onCheckedChange = { onSurface(plugin.pkg, "module", it) },
                )
            }
            val status = state.modules[plugin.pkg]
            if (
                status != null &&
                    status != dev.fughetta.dshcompose.domain.features.ModuleStatus.Active &&
                    status != dev.fughetta.dshcompose.domain.features.ModuleStatus.Disabled
            ) {
                Text(
                    stringResource(R.string.native_module_unavailable),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val surfaces =
                (plugin.actions.map { it.surface } +
                        (if (plugin.panels.isEmpty()) emptyList() else listOf("panels")) +
                        (if (plugin.strips.isEmpty()) emptyList() else listOf("composer.strip")) +
                        (if (plugin.turnNotes.isEmpty()) emptyList() else listOf("turn.note")))
                    .distinct()
            surfaces.forEach { surface ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(surfaceLabel(surface), Modifier.weight(1f))
                    Switch(
                        checked = state.enabled(plugin.pkg, surface),
                        onCheckedChange = { onSurface(plugin.pkg, surface, it) },
                        enabled = state.enabled(plugin.pkg, "module"),
                    )
                }
            }
            plugin.panels
                .filter { "plugin" in it.placements }
                .forEach { panel ->
                    TextButton(
                        onClick = { onPanel(plugin.pkg, panel.id) },
                        enabled = state.enabled(plugin.pkg, "panels"),
                    ) {
                        Text(panel.title)
                    }
                }
            state.issues[plugin.pkg]?.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
internal fun surfaceLabel(surface: String) =
    stringResource(
        when (surface) {
            "composer.actions" -> R.string.native_plugin_composer
            "composer.tools" -> R.string.native_plugin_tools
            "message.actions" -> R.string.native_plugin_message_actions
            "session.actions" -> R.string.native_plugin_session_actions
            "composer.strip" -> R.string.native_plugin_strip
            "turn.note" -> R.string.native_plugin_notes
            else -> R.string.native_plugin_panels
        },
    )

enum class NativePluginViewBudget(val bytes: Int, val nodes: Int, val depth: Int) {
    Panel(32768, 120, 8),
    ToolCard(8192, 40, 6),
    Strip(2048, 8, 3),
}

internal class ViewSession(val onAction: (NativePluginViewAction) -> Unit) {
    var drafts by mutableStateOf<Map<String, JsonElement>>(emptyMap())
    var errors by mutableStateOf<Set<String>>(emptySet())
    val pendingChanges = mutableMapOf<String, Job>()
    val pendingRemote = mutableMapOf<String, JsonElement>()
    var clean = emptySet<String>()
    var template: JsonElement = JsonNull
    var remote: JsonElement = JsonNull
    var lastActionRevision = 0L

    fun data(remote: JsonElement) =
        drafts.entries.fold(remote) { at, (path, value) -> nativePluginWrite(at, path, value) }
}

/**
 * Plugin controls stay separate from the model's reply chips; actions always go through
 * NativePlugin invoke.
 */

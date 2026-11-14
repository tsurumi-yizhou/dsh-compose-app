package dev.fughetta.dshcompose.ui

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.core.phone.R as PhoneR
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.presets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun presetName(preset: AgentPreset): String =
    presetNameResource(preset.id)?.let { stringResource(it) }
        ?: preset.name?.takeIf(String::isNotBlank)
        ?: preset.id

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AgentPresetsScreen(state: AgentPresetsUiState, onAction: (AppAction) -> Unit) {
    LifecycleStartEffect(Unit) {
        onAction(AppAction.ObserveAgentPresets(true))
        onStopOrDispose { onAction(AppAction.ObserveAgentPresets(false)) }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreDesignsystemR.string.agent_presets)) },
                navigationIcon = { UpButton() },
                actions = {
                    ActionIcon(
                        CoreDesignsystemR.drawable.ic_refresh,
                        stringResource(CoreDesignsystemR.string.refresh),
                        enabled = state.connected && !state.writing,
                    ) {
                        onAction(AppAction.RefreshAgentPresets)
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.connected && state.creatorAvailable && !state.loading && !state.writing)
                ExtendedFloatingActionButton(
                    onClick = { onAction(AppAction.OpenPresetEditor(PresetEditMode.Create)) },
                    icon = { Icon(painterResource(CoreDesignsystemR.drawable.ic_add), null) },
                    text = { Text(stringResource(R.string.preset_create)) },
                )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(inset, Spacing.Large, inset, 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                if (!state.connected)
                    item { Text(stringResource(CoreDesignsystemR.string.engine_not_connected)) }
                if (state.loading || state.writing)
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                state.bundlesError?.let {
                    item { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                state.notice?.let { item { Text(it) } }
                if (state.connected && !state.creatorAvailable && !state.loading)
                    item { Text(stringResource(R.string.preset_creator_unavailable)) }
                if (state.connected && state.presets.isEmpty() && !state.loading)
                    item { Text(stringResource(R.string.preset_no_rows)) }
                items(state.presets, key = { it.id }, contentType = { "preset" }) { preset ->
                    val bundle =
                        remember(preset.id, state.bundles) {
                            agentPresetOwner(preset.id, state.bundles)
                        }
                    val enabled = state.connected && !state.writing && !state.loading
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(Spacing.Large),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                        ) {
                            Text(presetName(preset), style = MaterialTheme.typography.titleMedium)
                            val description =
                                presetDescriptionResource(preset.id)?.let { stringResource(it) }
                                    ?: preset.description
                            description?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (preset.isDefault)
                                Text(
                                    stringResource(R.string.preset_default),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            if (preset.broken != null)
                                Text(
                                    stringResource(R.string.preset_broken),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                                TextButton(
                                    onClick = { onAction(AppAction.ViewAgentPreset(preset.id)) },
                                    enabled = enabled,
                                ) {
                                    Text(stringResource(R.string.preset_view))
                                }
                                TextButton(
                                    onClick = {
                                        onAction(AppAction.SetDefaultAgentPreset(preset.id))
                                    },
                                    enabled = enabled && !preset.isDefault && preset.broken == null,
                                ) {
                                    Text(stringResource(R.string.preset_set_default))
                                }
                                TextButton(
                                    onClick = {
                                        onAction(
                                            AppAction.OpenPresetEditor(
                                                PresetEditMode.Duplicate,
                                                preset.id,
                                            ),
                                        )
                                    },
                                    enabled = enabled && state.creatorAvailable,
                                ) {
                                    Text(stringResource(R.string.preset_duplicate))
                                }
                                if (bundle != null) {
                                    TextButton(
                                        onClick = {
                                            onAction(
                                                AppAction.OpenPresetEditor(
                                                    PresetEditMode.Edit,
                                                    preset.id,
                                                ),
                                            )
                                        },
                                        enabled = enabled && state.creatorAvailable,
                                    ) {
                                        Text(stringResource(R.string.preset_edit))
                                    }
                                }
                            }
                            if (bundle != null && state.supportsRemoval) {
                                HorizontalDivider()
                                TextButton(
                                    onClick = { onAction(AppAction.RemoveAgentPreset(preset.id)) },
                                    enabled = enabled && !preset.isDefault,
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Text(stringResource(R.string.preset_remove))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    state.detail?.let { PresetDocumentDialog(it, onAction) }
    state.editor?.let { PresetEditorDialog(it, state, onAction) }
    state.removal?.let { removal ->
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CloseAgentPresetRemoval) },
            title = { Text(stringResource(R.string.preset_remove)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                    Text(presetName(removal.preset))
                    Text(stringResource(R.string.preset_remove_details))
                    if (removal.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    removal.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(AppAction.ConfirmRemoveAgentPreset) },
                    enabled = !removal.busy,
                ) {
                    Text(stringResource(CoreDesignsystemR.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(AppAction.CloseAgentPresetRemoval) },
                    enabled = !removal.busy,
                ) {
                    Text(stringResource(CoreDesignsystemR.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDocumentDialog(state: AgentPresetDetail, onAction: (AppAction) -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val text = state.document?.content
    val prepared by
        produceState<Pair<String, List<String>>?>(null, text) {
            value = text?.let { withContext(Dispatchers.Default) { it to it.lines() } }
        }
    Dialog(
        onDismissRequest = { onAction(AppAction.CloseAgentPresetDetail) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(presetName(state.preset)) },
                    navigationIcon = {
                        ActionIcon(
                            CoreDesignsystemR.drawable.ic_close,
                            stringResource(CoreDesignsystemR.string.close),
                        ) {
                            onAction(AppAction.CloseAgentPresetDetail)
                        }
                    },
                    actions = {
                        ActionIcon(
                            PhoneR.drawable.ic_content_copy,
                            stringResource(CoreDesignsystemR.string.markdown_copy_code),
                            enabled = text != null,
                        ) {
                            text?.let {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", it)))
                                }
                            }
                        }
                    },
                )
            },
        ) { padding ->
            ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(inset, Spacing.Large),
                ) {
                    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error?.let {
                        item {
                            Text(it)
                            TextButton(onClick = { onAction(AppAction.RetryAgentPresetDocument) }) {
                                Text(stringResource(CoreDesignsystemR.string.refresh))
                            }
                        }
                    }
                    val lines = prepared?.takeIf { it.first == text }?.second.orEmpty()
                    items(lines.size, contentType = { "yaml-line" }) { index ->
                        SelectionContainer {
                            Text(
                                lines[index],
                                fontFamily =
                                    dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont
                                        .current,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditorDialog(
    editor: PresetEditorState,
    catalog: AgentPresetsUiState,
    onAction: (AppAction) -> Unit,
) {
    val values = editor.values
    val editable = !editor.busy && editor.createdSessionId == null
    val composition = editor.composition
    val switches = remember(composition) { composition?.switchable.orEmpty() }
    Dialog(
        onDismissRequest = { onAction(AppAction.ClosePresetEditor) },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(
                                when (editor.mode) {
                                    PresetEditMode.Create -> R.string.preset_create
                                    PresetEditMode.Duplicate -> R.string.preset_duplicate
                                    PresetEditMode.Edit -> R.string.preset_edit
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        ActionIcon(
                            CoreDesignsystemR.drawable.ic_close,
                            stringResource(CoreDesignsystemR.string.close),
                            enabled = !editor.busy,
                        ) {
                            onAction(AppAction.ClosePresetEditor)
                        }
                    },
                )
            },
            bottomBar = {
                Surface {
                    ReadablePane(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
                        inset ->
                        Button(
                            onClick = { onAction(AppAction.SubmitPreset) },
                            enabled =
                                editable &&
                                    !editor.loading &&
                                    composition != null &&
                                    catalog.creatorAvailable &&
                                    catalog.connected,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = inset, vertical = Spacing.Large),
                        ) {
                            Text(
                                stringResource(
                                    if (editor.mode == PresetEditMode.Edit)
                                        R.string.preset_start_update
                                    else R.string.preset_start_create,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(inset, Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                ) {
                    item {
                        OutlinedTextField(
                            values.name,
                            { onAction(AppAction.EditPresetField(PresetField.Name, it)) },
                            enabled = editable,
                            label = { Text(stringResource(R.string.preset_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            values.id,
                            { onAction(AppAction.EditPresetField(PresetField.Id, it)) },
                            enabled = editable && editor.mode != PresetEditMode.Edit,
                            label = { Text(stringResource(R.string.preset_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            values.description,
                            { onAction(AppAction.EditPresetField(PresetField.Description, it)) },
                            enabled = editable,
                            label = { Text(stringResource(R.string.preset_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                    }
                    if (editor.mode == PresetEditMode.Create)
                        item {
                            Text(
                                stringResource(R.string.preset_base),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                                items(
                                    catalog.presets.filter { it.broken == null },
                                    key = { it.id },
                                ) { preset ->
                                    FilterChip(
                                        selected = values.base == preset.id,
                                        onClick = {
                                            onAction(AppAction.ChoosePresetBase(preset.id))
                                        },
                                        enabled = editable,
                                        label = { Text(presetName(preset)) },
                                    )
                                }
                            }
                        }
                    if (editor.loading || editor.busy)
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    editor.error?.let {
                        item {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            if (composition == null && !editor.loading)
                                TextButton(onClick = { onAction(AppAction.RetryPresetBase) }) {
                                    Text(stringResource(CoreDesignsystemR.string.refresh))
                                }
                        }
                    }
                    if (editor.createdSessionId != null)
                        item {
                            TextButton(
                                onClick = { onAction(AppAction.OpenPresetCreatorSession) },
                                enabled = !editor.busy,
                            ) {
                                Text(stringResource(R.string.preset_open_created))
                            }
                        }
                    if (composition?.hasPersona == true) {
                        item {
                            OutlinedTextField(
                                values.prompt,
                                { onAction(AppAction.EditPresetField(PresetField.Prompt, it)) },
                                enabled = editable,
                                label = { Text(stringResource(R.string.preset_prompt)) },
                                minLines = 6,
                                maxLines = 12,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            ListItem(
                                modifier =
                                    Modifier.toggleable(
                                        values.complete,
                                        enabled = editable,
                                        role = Role.Switch,
                                        onValueChange = {
                                            onAction(AppAction.ReplacePresetPrompt(it))
                                        },
                                    ),
                                trailingContent = {
                                    Switch(
                                        values.complete,
                                        onCheckedChange = null,
                                        enabled = editable,
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.preset_complete))
                            }
                        }
                    }
                    for (kind in PresetRowKind.entries) {
                        val rows = switches.filter { it.kind == kind }
                        if (rows.isNotEmpty())
                            item {
                                Text(
                                    stringResource(
                                        when (kind) {
                                            PresetRowKind.Tool -> R.string.preset_tools
                                            PresetRowKind.Skill -> R.string.preset_skills
                                            PresetRowKind.Context -> R.string.preset_context
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        itemsIndexed(
                            rows,
                            key = { index, row -> "${kind.name}-$index-${row.id}" },
                            contentType = { _, _ -> "preset-switch" },
                        ) { _, row ->
                            val enabled = row.id in values.enabled
                            ListItem(
                                modifier =
                                    Modifier.toggleable(enabled, editable, Role.Switch) {
                                        onAction(AppAction.TogglePresetRow(row.id, it))
                                    },
                                trailingContent = {
                                    Switch(enabled, onCheckedChange = null, enabled = editable)
                                },
                            ) {
                                Text(
                                    presetRowLabelResource(row)?.let { stringResource(it) }
                                        ?: row.id,
                                )
                            }
                        }
                    }
                    if (composition != null)
                        item {
                            Text(
                                stringResource(R.string.preset_preserved),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }
            }
        }
    }
    if (editor.discard)
        AlertDialog(
            onDismissRequest = { onAction(AppAction.KeepPresetEditor) },
            title = { Text(stringResource(R.string.preset_discard_title)) },
            confirmButton = {
                TextButton(onClick = { onAction(AppAction.DiscardPresetEditor) }) {
                    Text(stringResource(R.string.preset_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AppAction.KeepPresetEditor) }) {
                    Text(stringResource(CoreDesignsystemR.string.cancel))
                }
            },
        )
}

@Composable
fun AgentPresetsRoute(
    source: StateFlow<AgentPresetsUiState>,
    onAction: (AppAction) -> Unit,
) {
    AgentPresetsScreen(source.collectAsStateWithLifecycle().value, onAction)
}

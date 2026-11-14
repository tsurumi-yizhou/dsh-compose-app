package dev.fughetta.dshcompose.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.commands.CommandsPalette
import dev.fughetta.dshcompose.ui.commands.CommandsUiState
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.composer.editReferenceDraft
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.conversation.ChatUiState
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChatComposer(
    id: String,
    state: ChatUiState,
    references: ReferencePickerState?,
    commands: CommandsUiState?,
    goal: GoalUiState?,
    agentPresets: StateFlow<AgentPresetsUiState>,
    nativePlugins: dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState,
    mediaGeneration: dev.fughetta.dshcompose.ui.media.MediaGenerationState,
    usage: dev.fughetta.dshcompose.ui.transcript.UsageState,
    trajectoryState: MutableState<String?>,
    composerState: MutableState<TextFieldValue>,
    spansState: MutableState<PersistentList<ReferenceSpan>>,
    composerFocus: FocusRequester,
    jumpToLatest: () -> Unit,
    onAction: (AppAction) -> Unit,
) {
    var composer by composerState
    var spans by spansState
    // The composer sits closer to the keyboard while it is up; without it the spacing
    // around the composer is unchanged.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < ComposerChipsInlineWidth
        var locationOpen by remember(id) { mutableStateOf(false) }
        var toolsOpen by remember(id) { mutableStateOf(false) }
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        BackHandler(compact && toolsOpen) { toolsOpen = false }
        LaunchedEffect(compact, state.readOnly) {
            if (!compact || state.readOnly) toolsOpen = false
        }
        Column(
            Modifier.fillMaxWidth()
                .wrapContentWidth()
                .widthIn(max = ReadableWidth)
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = if (imeVisible) 6.dp else 12.dp,
                ),
        ) {
            if (usage.sessionId == id)
                dev.fughetta.dshcompose.ui.transcript.TranscriptBalanceBanner(
                    usage,
                    onAction = { onAction(AppAction.Usage(it)) },
                    onTopUp = { onAction(AppAction.TopUpBalance) },
                )
            dev.fughetta.dshcompose.ui.messages.PendingPromptsPanel(
                state.pendingPrompts,
                { onAction(AppAction.PendingPrompts(it)) },
            )
            if (state.forkSourceId != null)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    state.forkWarning?.let {
                        Text(
                            it,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { onAction(AppAction.ReturnToOriginal(id)) }) {
                        Text(stringResource(R.string.message_return_original))
                    }
                }
            state.quote?.let { quote ->
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            quote,
                            Modifier.weight(1f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onAction(AppAction.ClearQuote(id)) }) {
                            Text(stringResource(CoreDesignsystemR.string.close))
                        }
                    }
                }
            }
            if (state.readOnly)
                Text(
                    stringResource(R.string.subagent_read_only),
                    style = MaterialTheme.typography.labelMedium,
                )
            if (state.running)
                state.turns.current?.let { trace ->
                    dev.fughetta.dshcompose.ui.transcript.TranscriptActivityStrip(
                        trace,
                        onOpen = { trajectoryState.value = trace.id },
                    )
                }
            if (!state.syncing && goal?.visible == true) {
                GoalDock(goal, onAction)
                Spacer(Modifier.height(8.dp))
            }
            if (!state.syncing)
                state.todos
                    ?.takeIf { it.total > 0 }
                    ?.let { todos ->
                        TodoDock(id, todos, state.running)
                        Spacer(Modifier.height(8.dp))
                    }
            if (!state.readOnly)
                dev.fughetta.dshcompose.ui.media.GenerationControls(mediaGeneration, id) {
                    onAction(AppAction.Media(it))
                }
            dev.fughetta.dshcompose.ui.nativeplugins.NativePluginPanels(
                nativePlugins,
                setOf("session.tab", "chat.panel"),
            ) { pkg, panel ->
                onAction(AppAction.NativePluginPanel(pkg, panel))
            }
            dev.fughetta.dshcompose.ui.nativeplugins.NativePluginStrips(
                nativePlugins,
                onAction = {
                    onAction(
                        AppAction.NativePluginInvoke(
                            it,
                            dev.fughetta.dshcompose.domain.nativeplugins.NativePluginScope(id),
                        ),
                    )
                },
                onPanel = { pkg, panel -> onAction(AppAction.NativePluginPanel(pkg, panel)) },
            )
            val registeredActions = LocalComposerActions.current
            val actionContext =
                ComposerActionContext(
                    id,
                    composer.selection.min,
                    composer.selection.max,
                    state.readOnly,
                    state.sending,
                    state.syncing,
                )
            for (surface in listOf("composer.actions", "composer.tools", "session.actions")) {
                val actions =
                    registeredActions
                        .filter {
                            it.surface == surface &&
                                (it.plugin == null ||
                                    nativePlugins.enabled(requireNotNull(it.plugin), surface))
                        }
                        .take(if (surface == "session.actions") 32 else 8)
                if (actions.isNotEmpty())
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        actions.forEach { action ->
                            key(action.id) {
                                AssistChip(
                                    onClick = {
                                        run {
                                            val invoked = action.invoke(actionContext)
                                            if (invoked is AppAction.InsertLocation)
                                                locationOpen = true
                                            else onAction(invoked)
                                        }
                                    },
                                    enabled =
                                        action.enabled(actionContext) &&
                                            (action.busyKey == null ||
                                                action.busyKey !in nativePlugins.busy),
                                    label = {
                                        Text(
                                            action.label(
                                                androidx.compose.ui.platform.LocalContext.current,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
            }
            commands?.let { CommandsPalette(it, !state.sending && !state.syncing, onAction) }
            references?.let { InlineReferences(it, onAction) }
            val hasContent =
                composer.text.isNotBlank() ||
                    !state.quote.isNullOrBlank() ||
                    state.attachments.isNotEmpty()
            val canSend = !state.readOnly && hasContent && !state.syncing && !state.sending
            val showStop = state.running && !state.readOnly && !hasContent
            fun send() {
                jumpToLatest()
                onAction(AppAction.Send(id))
            }
            // Applies a text field edit, keeping the draft's reference spans in sync.
            fun updateComposer(next: TextFieldValue) {
                val edited =
                    editReferenceDraft(
                        ReferenceInput(
                            composer.text,
                            composer.selection.start,
                            composer.selection.end,
                        ),
                        ReferenceInput(
                            next.text,
                            next.selection.start,
                            next.selection.end,
                        ),
                        spans,
                        next.composition != null,
                    )
                spans = edited.spans
                composer =
                    next.copy(
                        text = edited.input.text,
                        selection = TextRange(edited.input.start, edited.input.end),
                        composition = if (edited.widened) null else next.composition,
                    )
                onAction(
                    AppAction.ChangeDraft(
                        id,
                        composer.text,
                        spans,
                        composer.selection.start,
                        composer.selection.end,
                        composer.composition != null,
                    ),
                )
            }
            // Breaks the line at the caret. Only Ctrl+Enter reaches this: a soft keyboard has
            // no Ctrl, and its Enter sends through the Send IME action instead.
            fun breakLine() {
                val selection = composer.selection
                updateComposer(
                    composer.copy(
                        text = composer.text.replaceRange(selection.min, selection.max, "\n"),
                        selection = TextRange(selection.min + 1),
                        composition = null,
                    ),
                )
            }
            if (locationOpen)
                ComposerLocationDialog(
                    onInsert = { value ->
                        val selection = composer.selection
                        updateComposer(
                            composer.copy(
                                text =
                                    composer.text.replaceRange(
                                        selection.min,
                                        selection.max,
                                        value + " ",
                                    ),
                                selection = TextRange(selection.min + value.length + 1),
                                composition = null,
                            ),
                        )
                        locationOpen = false
                        composerFocus.requestFocus()
                    },
                    onDismiss = { locationOpen = false },
                )
            if (compact) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentPresetChip(
                        id,
                        state.agentPreset,
                        agentPresets,
                        !state.readOnly,
                        !state.syncing && !state.sending && state.messages.isEmpty(),
                        onAction,
                        compact = true,
                    )
                    Spacer(Modifier.width(4.dp))
                    FilledTonalIconButton(
                        onClick = { onAction(AppAction.OpenModelPicker(id)) },
                        enabled = !state.readOnly && !state.sending,
                    ) {
                        Icon(
                            painterResource(CoreDesignsystemR.drawable.ic_engine),
                            stringResource(CoreDesignsystemR.string.select_model) +
                                ": " +
                                (state.model?.model ?: ""),
                        )
                    }
                }
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(4.dp)) {
                    TextField(
                        value = composer,
                        onValueChange = ::updateComposer,
                        readOnly = state.readOnly,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
                        visualTransformation =
                            ReferenceVisualTransformation(
                                spans,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        modifier =
                            Modifier.fillMaxWidth()
                                .focusRequester(composerFocus)
                                .onFocusChanged { if (it.isFocused) toolsOpen = false }
                                // Enter sends on both keyboards, Ctrl+Enter breaks the line;
                                // an IME's composing Enter stays with the IME. A soft
                                // keyboard's Enter arrives as the Send action above, not as
                                // a key event.
                                .onPreviewKeyEvent { event ->
                                    if (
                                        (event.key != Key.Enter && event.key != Key.NumPadEnter) ||
                                            composer.composition != null ||
                                            event.nativeKeyEvent.flags and
                                                android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0
                                    )
                                        return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (event.isCtrlPressed) {
                                            if (!state.readOnly) breakLine()
                                        } else if (canSend) send()
                                    }
                                    true
                                },
                        placeholder = { Text(stringResource(R.string.message_hint)) },
                        maxLines = if (compact) 4 else 6,
                        leadingIcon =
                            if (compact)
                                ({
                                    IconButton(
                                        onClick = {
                                            toolsOpen = !toolsOpen
                                            if (toolsOpen) {
                                                focusManager.clearFocus()
                                                keyboard?.hide()
                                            }
                                        },
                                        enabled =
                                            !state.readOnly && !state.sending && !state.syncing,
                                    ) {
                                        Icon(
                                            painterResource(
                                                if (toolsOpen) CoreDesignsystemR.drawable.ic_close
                                                else CoreDesignsystemR.drawable.ic_add,
                                            ),
                                            stringResource(
                                                if (toolsOpen) CoreDesignsystemR.string.close
                                                else R.string.composer_add,
                                            ),
                                        )
                                    }
                                })
                            else null,
                        trailingIcon =
                            if (compact)
                                ({
                                    FilledIconButton(
                                        onClick = {
                                            if (showStop) onAction(AppAction.StopTurn(id))
                                            else send()
                                        },
                                        enabled = if (showStop) !state.syncing else canSend,
                                    ) {
                                        Icon(
                                            painterResource(
                                                if (showStop) R.drawable.ic_stop
                                                else R.drawable.ic_send,
                                            ),
                                            stringResource(
                                                if (showStop) R.string.stop else R.string.send,
                                            ),
                                        )
                                    }
                                })
                            else null,
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                    )
                    // The chips share what is left beside the buttons and shrink to fit, so
                    // send stays at the end; where that would cut them to an ellipsis they
                    // take their own line.
                    if (!compact)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stacked = maxWidth < ComposerChipsInlineWidth
                            val chips =
                                @Composable { modifier: Modifier ->
                                    Row(
                                        modifier,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AgentPresetChip(
                                            id,
                                            state.agentPreset,
                                            agentPresets,
                                            !state.readOnly,
                                            // The Host switches only a conversation nothing has
                                            // been sent in.
                                            !state.syncing &&
                                                !state.sending &&
                                                state.messages.isEmpty(),
                                            onAction,
                                            // On its own line the mode keeps its width and the
                                            // longer model name gets the rest.
                                            if (stacked) Modifier
                                            else Modifier.weight(1f, fill = false),
                                        )
                                        AssistChip(
                                            modifier = Modifier.weight(1f, fill = false),
                                            onClick = { onAction(AppAction.OpenModelPicker(id)) },
                                            enabled = !state.readOnly && !state.sending,
                                            label = {
                                                val label =
                                                    state.model?.let { model ->
                                                        model.model +
                                                            (model.reasoningEffort?.let {
                                                                " · " + effortName(it)
                                                            } ?: "")
                                                    }
                                                        ?: stringResource(
                                                            CoreDesignsystemR.string.select_model,
                                                        )
                                                Text(
                                                    label,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            trailingIcon = {
                                                Icon(
                                                    painterResource(
                                                        CoreDesignsystemR.drawable.ic_chevron_right,
                                                    ),
                                                    null,
                                                    Modifier.size(AssistChipDefaults.IconSize)
                                                        .rotate(90f),
                                                )
                                            },
                                        )
                                    }
                                }
                            Column {
                                if (stacked)
                                    chips(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (
                                        mediaGeneration.catalog.status !=
                                            dev.fughetta.dshcompose.domain.media.MediaCatalogStatus
                                                .Missing
                                    ) {
                                        IconButton(
                                            onClick = {
                                                onAction(
                                                    AppAction.Media(
                                                        if (
                                                            mediaGeneration.catalog.entries
                                                                .isEmpty()
                                                        )
                                                            dev.fughetta.dshcompose.ui.media
                                                                .MediaAction
                                                                .Settings
                                                        else
                                                            dev.fughetta.dshcompose.ui.media
                                                                .MediaAction
                                                                .Parameters(id),
                                                    ),
                                                )
                                            },
                                            enabled =
                                                !state.readOnly && !state.sending && !state.syncing,
                                        ) {
                                            Icon(
                                                painterResource(
                                                    CoreDesignsystemR.drawable.ic_s_image,
                                                ),
                                                stringResource(R.string.media_generation),
                                            )
                                        }
                                    }
                                    registeredActions
                                        .filter { it.surface == "composer.toolbar" }
                                        .forEach { action ->
                                            key(action.id) {
                                                val label =
                                                    action.label(
                                                        androidx.compose.ui.platform.LocalContext
                                                            .current,
                                                    )
                                                if (action.icon != null)
                                                    IconButton(
                                                        onClick = {
                                                            run {
                                                                val invoked =
                                                                    action.invoke(actionContext)
                                                                if (
                                                                    invoked
                                                                        is AppAction.InsertLocation
                                                                )
                                                                    locationOpen = true
                                                                else onAction(invoked)
                                                            }
                                                        },
                                                        enabled = action.enabled(actionContext),
                                                    ) {
                                                        Icon(
                                                            painterResource(
                                                                requireNotNull(action.icon),
                                                            ),
                                                            label,
                                                        )
                                                    }
                                                else
                                                    TextButton(
                                                        onClick = {
                                                            run {
                                                                val invoked =
                                                                    action.invoke(actionContext)
                                                                if (
                                                                    invoked
                                                                        is AppAction.InsertLocation
                                                                )
                                                                    locationOpen = true
                                                                else onAction(invoked)
                                                            }
                                                        },
                                                        enabled = action.enabled(actionContext),
                                                    ) {
                                                        Text(label)
                                                    }
                                            }
                                        }
                                    if (stacked) Spacer(Modifier.weight(1f))
                                    else chips(Modifier.weight(1f))
                                    FilledIconButton(
                                        onClick = {
                                            if (showStop) onAction(AppAction.StopTurn(id))
                                            else send()
                                        },
                                        enabled = if (showStop) !state.syncing else canSend,
                                    ) {
                                        Icon(
                                            painterResource(
                                                if (showStop) R.drawable.ic_stop
                                                else R.drawable.ic_send,
                                            ),
                                            stringResource(
                                                if (showStop) R.string.stop else R.string.send,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            AnimatedVisibility(visible = compact && toolsOpen) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                ) {
                    FlowRow(
                        modifier =
                            Modifier.heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        registeredActions
                            .filter { it.surface == "composer.toolbar" }
                            .forEach { action ->
                                key(action.id) {
                                    ComposerPanelButton(
                                        label =
                                            action.label(
                                                androidx.compose.ui.platform.LocalContext.current,
                                            ),
                                        icon = action.icon,
                                        enabled =
                                            !state.readOnly &&
                                                !state.sending &&
                                                !state.syncing &&
                                                action.enabled(actionContext),
                                    ) {
                                        toolsOpen = false
                                        run {
                                            val invoked = action.invoke(actionContext)
                                            if (invoked is AppAction.InsertLocation)
                                                locationOpen = true
                                            else onAction(invoked)
                                        }
                                    }
                                }
                            }
                        if (mediaGeneration.catalog.status != MediaCatalogStatus.Missing) {
                            ComposerPanelButton(
                                stringResource(R.string.media_generation),
                                CoreDesignsystemR.drawable.ic_s_image,
                                !state.readOnly && !state.sending && !state.syncing,
                            ) {
                                toolsOpen = false
                                onAction(
                                    AppAction.Media(
                                        if (mediaGeneration.catalog.entries.isEmpty())
                                            dev.fughetta.dshcompose.ui.media.MediaAction.Settings
                                        else
                                            dev.fughetta.dshcompose.ui.media.MediaAction.Parameters(
                                                id,
                                            ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Width below which the mode and model chips occupy their own line. */
private val ComposerChipsInlineWidth = 480.dp

@Composable
private fun ComposerPanelButton(label: String, icon: Int?, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(80.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor =
            if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(26.dp))
            else Text(if (label == "@") "@" else "+", style = MaterialTheme.typography.titleLarge)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

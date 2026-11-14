package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.window.core.layout.WindowSizeClass
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow
import dev.fughetta.dshcompose.domain.transcript.TranscriptRowPlan
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.commands.CommandsUiState
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.composer.parseDraftReferences
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.conversation.ChatUiState
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import dev.fughetta.dshcompose.ui.sessions.SessionOperation
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
internal const val HISTORY_PREFETCH_ROWS = 8

@Composable
fun ChatScreen(
    id: String,
    state: ChatUiState,
    workspaces: WorkspacesUiState,
    references: ReferencePickerState?,
    referenceLabels: dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex,
    commands: CommandsUiState?,
    goal: GoalUiState?,
    agentPresets: StateFlow<AgentPresetsUiState>,
    nativePlugins: dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState,
    mediaGeneration: dev.fughetta.dshcompose.ui.media.MediaGenerationState,
    usage: dev.fughetta.dshcompose.ui.transcript.UsageState,
    backgroundNet: dev.fughetta.dshcompose.ui.runtime.BackgroundNetReason?,
    onMenu: (() -> Unit)?,
    onDetails: () -> Unit,
    onAction: (AppAction) -> Unit,
) {
    val generatedMediaByCall =
        remember(state.generatedMedia) { state.generatedMedia.values.associateBy { it.toolCallId } }
    LifecycleStartEffect(id) {
        onAction(AppAction.ObserveGoal(id, true))
        onStopOrDispose { onAction(AppAction.ObserveGoal(id, false)) }
    }
    fun restoredSpans() =
        (state.draftSpans +
                parseDraftReferences(state.draft).filter { parsed ->
                    state.draftSpans.none { it.start < parsed.end && parsed.start < it.end }
                })
            .sortedBy { it.start }
            .toPersistentList()
    val spansState = remember(id) { mutableStateOf(restoredSpans()) }
    var spans by spansState
    val composerState =
        remember(id) { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    var composer by composerState
    val composerFocus = remember(id) { FocusRequester() }
    LaunchedEffect(state.draft, state.draftCursor) {
        if (
            composer.text != state.draft ||
                state.draftCursor != null &&
                    composer.selection !=
                        TextRange(requireNotNull(state.draftCursor).coerceIn(0, state.draft.length))
        ) {
            spans = restoredSpans()
            composer =
                TextFieldValue(
                    state.draft,
                    TextRange(
                        (state.draftCursor ?: state.draft.length).coerceIn(0, state.draft.length),
                    ),
                )
            if (state.draftCursor != null) composerFocus.requestFocus()
        }
    }
    LifecycleStartEffect(id) {
        onAction(AppAction.VisibleSession(id, true))
        onStopOrDispose { onAction(AppAction.VisibleSession(id, false)) }
    }
    val listState = rememberLazyListState()
    val expandedProcessesState = remember(id) { mutableStateOf(emptySet<String>()) }
    var expandedProcesses by expandedProcessesState
    val manualPauseState = remember(id) { mutableLongStateOf(0) }
    var manualPause by manualPauseState
    val historyAnchor = remember(id) { arrayOfNulls<Pair<String, Int>>(1) }
    val trajectoryState = remember(id) { mutableStateOf<String?>(null) }
    var trajectory by trajectoryState
    val processScope = rememberCoroutineScope()
    val plan =
        remember(state.turns, state.messages.size, expandedProcesses, state.foldCompletedTurns) {
            dev.fughetta.dshcompose.domain.transcript.TranscriptRowPlan(
                state.turns,
                state.messages.size,
                expandedProcesses,
                state.foldCompletedTurns,
            )
        }
    val currentPlan by rememberUpdatedState(plan)
    val currentChat by rememberUpdatedState(state)
    LaunchedEffect(id, listState) {
        snapshotFlow {
            val chat = currentChat
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info ->
                    val position = info.index - if (chat.hasMoreHistory) 1 else 0
                    if (position !in 0 until currentPlan.count) null
                    else
                        when (val row = currentPlan.rowAt(position)) {
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Item ->
                                chat.messages
                                    .getOrNull(row.rawIndex)
                                    ?.takeIf { it.key == info.key }
                                    ?.turnId
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Process ->
                                row.trace.id.takeIf { row.segment.key == info.key }
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Footnote ->
                                row.trace.id.takeIf { "footnote:${row.trace.startSeq}" == info.key }
                        }
                }
                .distinct()
        }
            .distinctUntilChanged()
            .collect { onAction(AppAction.NativePluginVisibleTurns(id, it)) }
    }
    DisposableEffect(id) {
        onDispose { onAction(AppAction.NativePluginVisibleTurns(id, emptyList())) }
    }
    // Pages older history in while the reader nears the top, before the header row is reached.
    // A failed page waits for the error to clear (or a tap on the header) instead of retrying.
    LaunchedEffect(id, listState) {
        snapshotFlow {
            val chat = currentChat
            chat.hasMoreHistory &&
                !chat.loadingHistory &&
                !chat.syncing &&
                chat.error == null &&
                listState.firstVisibleItemIndex < HISTORY_PREFETCH_ROWS &&
                (listState.lastScrolledBackward ||
                    !listState.canScrollBackward && !listState.canScrollForward)
        }
            .distinctUntilChanged()
            .collect { if (it) onAction(AppAction.LoadHistory(id)) }
    }
    // Track the first real row while a page loads, so the reader can keep scrolling meanwhile.
    // Capture stops once the page lands: the list then holds the header row in place, and the
    // anchor below is what keeps the reader's row where it was.
    LaunchedEffect(id, state.loadingHistory) {
        if (state.loadingHistory) {
            val oldest = currentChat.messages.firstOrNull()?.key
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key != "older-history" }
                    ?.takeIf { currentChat.messages.firstOrNull()?.key == oldest }
                    ?.let { it.key.toString() to it.offset }
            }
                .collect { anchor -> anchor?.let { historyAnchor[0] = it } }
        } else {
            withFrameNanos {}
            historyAnchor[0] = null
        }
    }
    LaunchedEffect(id, state.historyEpoch, state.messages.firstOrNull()?.key) {
        historyAnchor[0]?.let { (key, offset) ->
            plan.positionOfKey(key)?.let { position ->
                listState.requestScrollToItem(
                    position + if (state.hasMoreHistory) 1 else 0,
                    -offset,
                )
            }
            historyAnchor[0] = null
        }
    }
    val tailIndex =
        plan.count +
            state.approvals.size +
            state.questions.size +
            if (state.hasMoreHistory) 1 else 0
    val jumpToLatest = rememberChatTailFollow(id, listState, tailIndex, manualPause)
    val showJump by remember { derivedStateOf { listState.canScrollForward } }
    var menu by rememberSaveable(id) { mutableStateOf(false) }
    val twoPane =
        currentWindowAdaptiveInfoV2()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val draft = id == DRAFT_SESSION_ID
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.title
                            ?: stringResource(
                                if (draft) CoreDesignsystemR.string.new_conversation
                                else R.string.conversation,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onMenu != null)
                        ActionIcon(
                            R.drawable.ic_menu,
                            stringResource(R.string.conversations),
                            onClick = onMenu,
                        )
                    else if (!twoPane) UpButton()
                },
                actions = {
                    if (usage.sessionId == id)
                        dev.fughetta.dshcompose.ui.transcript.TranscriptContextRing(
                            usage.usage,
                            {
                                onAction(
                                    AppAction.Usage(
                                        dev.fughetta.dshcompose.ui.transcript.UsageAction.Open,
                                    ),
                                )
                            },
                        )
                    // A draft has nothing on the host yet to inspect, rename or archive.
                    if (draft) return@TopAppBar
                    ActionIcon(
                        CoreDesignsystemR.drawable.ic_info,
                        stringResource(R.string.session_details),
                        onClick = onDetails,
                    )
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(CoreDesignsystemR.drawable.ic_more),
                            stringResource(R.string.session_actions),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.transcript_fold_completed)) },
                            leadingIcon = { Checkbox(state.foldCompletedTurns, null) },
                            onClick = {
                                menu = false
                                onAction(AppAction.FoldCompletedTurns(!state.foldCompletedTurns))
                            },
                        )
                        val archived = id in workspaces.archived
                        val pinAction =
                            if (id in workspaces.pinned) WorkspaceSessionOperation.Unpin
                            else WorkspaceSessionOperation.Pin
                        val archiveAction =
                            if (archived) WorkspaceSessionOperation.Restore
                            else WorkspaceSessionOperation.Archive
                        for (operation in listOf(pinAction, archiveAction)) DropdownMenuItem(
                            text = { Text(stringResource(workspaceSessionLabel(operation))) },
                            enabled =
                                workspaces.connected &&
                                    !workspaces.loading &&
                                    !workspaces.writing &&
                                    (!archived || operation != WorkspaceSessionOperation.Pin),
                            onClick = {
                                menu = false
                                onAction(AppAction.ManageWorkspaceSession(id, operation))
                            },
                        )
                        for (operation in SessionOperation.entries) DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (operation) {
                                            SessionOperation.Rename -> R.string.rename_session
                                            SessionOperation.Fork -> R.string.fork_session
                                            SessionOperation.Delete -> R.string.delete_session
                                        },
                                    ),
                                )
                            },
                            enabled = !state.syncing,
                            onClick = {
                                menu = false
                                onAction(AppAction.ManageSession(id, operation))
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            // Consume what the padding already covers so imePadding does not add the
            // navigation bar height a second time while the keyboard is up.
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        ) {
            backgroundNet?.let { BackgroundNetLine(it, onAction) }
            if (state.defaultModelSaveFailed)
                Text(
                    stringResource(R.string.model_default_save_failed),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            ChatTranscriptContent(
                id,
                state,
                nativePlugins,
                plan,
                listState,
                expandedProcessesState,
                manualPauseState,
                trajectoryState,
                historyAnchor,
                processScope,
                referenceLabels,
                generatedMediaByCall,
                showJump,
                jumpToLatest,
                onAction,
            )
            if (state.attachments.isNotEmpty())
                LazyRow(
                    Modifier.fillMaxWidth()
                        .wrapContentWidth()
                        .widthIn(max = ReadableWidth)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.attachments, key = { it.id }) { attachment ->
                        Column {
                            val remove = stringResource(CoreDesignsystemR.string.remove)
                            InputChip(
                                selected = false,
                                onClick = {
                                    onAction(AppAction.RemoveAttachment(id, attachment.id))
                                },
                                enabled = !state.sending,
                                label = {
                                    Text(
                                        attachment.name,
                                        Modifier.widthIn(max = 180.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        painterResource(CoreDesignsystemR.drawable.ic_close),
                                        remove,
                                        Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                            attachment.progress?.let { progress ->
                                LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.width(180.dp),
                                )
                            }
                        }
                    }
                }
            ChatComposer(
                id,
                state,
                references,
                commands,
                goal,
                agentPresets,
                nativePlugins,
                mediaGeneration,
                usage,
                trajectoryState,
                composerState,
                spansState,
                composerFocus,
                jumpToLatest,
                onAction,
            )
        }
    }
    trajectory?.let { turnId ->
        state.turns.trace(turnId)?.let { trace ->
            dev.fughetta.dshcompose.ui.transcript.TranscriptTrajectorySheet(
                trace,
                onDismiss = { trajectory = null },
            )
        }
    }
}

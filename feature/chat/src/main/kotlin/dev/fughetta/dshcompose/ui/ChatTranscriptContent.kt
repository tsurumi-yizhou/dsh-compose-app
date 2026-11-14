package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow
import dev.fughetta.dshcompose.domain.transcript.TranscriptRowPlan
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.conversation.ChatUiState
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.ChatTranscriptContent(
    id: String,
    state: ChatUiState,
    nativePlugins: dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState,
    plan: TranscriptRowPlan,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedProcessesState: MutableState<Set<String>>,
    manualPauseState: MutableState<Long>,
    trajectoryState: MutableState<String?>,
    historyAnchor: Array<Pair<String, Int>?>,
    processScope: kotlinx.coroutines.CoroutineScope,
    referenceLabels: ReferenceLabelIndex,
    generatedMediaByCall: Map<String, MediaTranscriptItem>,
    showJump: Boolean,
    jumpToLatest: () -> Unit,
    onAction: (AppAction) -> Unit,
) {
    var expandedProcesses by expandedProcessesState
    var manualPause by manualPauseState
    var trajectory by trajectoryState
    // Reading width instead of the old fixed 720 dp column: the transcript keeps a
    // readable measure while the composer row below can use the wider pane.
    CenteredContent(
        Modifier.weight(1f).fillMaxWidth(),
        maxContentWidth = ContentWidths.Reading,
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(0.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.hasMoreHistory)
                    item(key = "older-history") {
                        if (state.loadingHistory)
                            Box(
                                Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val loading = stringResource(R.string.loading_history)
                                CircularProgressIndicator(
                                    Modifier.size(24.dp).semantics { contentDescription = loading },
                                    strokeWidth = 2.dp,
                                )
                            }
                        else
                            TextButton(
                                onClick = {
                                    manualPause++
                                    historyAnchor[0] =
                                        listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key != "older-history" }
                                            ?.let { it.key.toString() to it.offset }
                                    onAction(AppAction.LoadHistory(id))
                                },
                                enabled = !state.syncing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.load_history))
                            }
                    }
                items(
                    count = plan.count,
                    key = { position ->
                        when (val row = plan.rowAt(position)) {
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Item ->
                                state.messages[row.rawIndex].key
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Process ->
                                row.segment.key
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Footnote ->
                                "footnote:${row.trace.startSeq}"
                        }
                    },
                    contentType = { position ->
                        when (val row = plan.rowAt(position)) {
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Item ->
                                if (row.cardOnly) "tool-card" else state.messages[row.rawIndex].role
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Process ->
                                "process"
                            is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Footnote ->
                                "footnote"
                        }
                    },
                ) { position ->
                    when (val planned = plan.rowAt(position)) {
                        is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Process ->
                            dev.fughetta.dshcompose.ui.transcript.TranscriptProcessRow(
                                planned.segment,
                                onToggle = {
                                    manualPause++
                                    processScope.launch {
                                        listState.scroll(
                                            androidx.compose.foundation.MutatePriority.UserInput,
                                        ) {
                                            expandedProcesses =
                                                if (planned.segment.key in expandedProcesses)
                                                    expandedProcesses - planned.segment.key
                                                else expandedProcesses + planned.segment.key
                                            withFrameNanos {}
                                        }
                                    }
                                },
                            )
                        is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Footnote ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                dev.fughetta.dshcompose.ui.transcript.TranscriptChangesCard(
                                    planned.trace.changes,
                                    onOpenFile = { path ->
                                        onAction(
                                            AppAction.TranscriptArtifact(
                                                id,
                                                dev.fughetta.dshcompose.domain.sessions
                                                    .TranscriptArtifact(
                                                        path,
                                                        dev.fughetta.dshcompose.domain.sessions
                                                            .TranscriptArtifactOrigin
                                                            .Linked,
                                                        planned.trace.id,
                                                    ),
                                                dev.fughetta.dshcompose.ui.transcript
                                                    .TranscriptArtifactAction
                                                    .Preview,
                                            ),
                                        )
                                    },
                                )
                                dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactsCard(
                                    planned.trace.artifacts,
                                    onAction = { file, action ->
                                        onAction(
                                            AppAction.TranscriptArtifact(
                                                id,
                                                file,
                                                action,
                                            ),
                                        )
                                    },
                                    hiddenPaths = state.generatedMediaPaths,
                                )
                                RegisteredMessage(
                                    MessageRenderContext.Turn(
                                        planned.trace,
                                        { trajectory = planned.trace.id },
                                        nativePlugins,
                                    ),
                                )
                            }
                        is dev.fughetta.dshcompose.domain.transcript.TranscriptPlannedRow.Item -> {
                            val rowIndex = planned.rawIndex
                            val row = state.messages[rowIndex]
                            val pending = state.viewReply?.takeIf { it.messageKey == row.key }
                            val generation =
                                dev.fughetta.dshcompose.ui.markdown.LocalReplyImages.current
                                    .generation
                            val context =
                                DynamicUiContext(
                                    uiInteraction(
                                        row,
                                        state.messageOrder,
                                        state.running ||
                                            state.syncing ||
                                            state.sending && pending == null,
                                    ),
                                    row.streaming,
                                    pending?.text?.let(::parseUiReply),
                                    pending?.busy == true,
                                    onReply = { source, reply ->
                                        onAction(
                                            AppAction.ReplyToView(
                                                id,
                                                row.key,
                                                generation,
                                                source,
                                                reply.path,
                                                reply.selected,
                                                reply.freetext,
                                                reply.confirmed,
                                            ),
                                        )
                                    },
                                    submitted = pending != null,
                                    onResend = { source ->
                                        onAction(
                                            AppAction.AskViewResend(
                                                id,
                                                row.key,
                                                generation,
                                                source,
                                            ),
                                        )
                                    },
                                    onGenuiAction = { source, input ->
                                        onAction(
                                            AppAction.GenuiAction(
                                                id,
                                                row.key,
                                                generation,
                                                source,
                                                input,
                                            ),
                                        )
                                    },
                                )
                            CompositionLocalProvider(LocalDynamicUi provides context) {
                                dev.fughetta.dshcompose.ui.appearance.AppearanceChatScale {
                                    Column(
                                        Modifier.fillMaxWidth(),
                                        horizontalAlignment =
                                            if (row.role == ChatRole.User) Alignment.End
                                            else Alignment.Start,
                                    ) {
                                        ChatMessage(
                                            id,
                                            row,
                                            referenceLabels,
                                            onMenu = {
                                                onAction(
                                                    AppAction.OpenMessageMenu(
                                                        id,
                                                        row.key,
                                                    ),
                                                )
                                            },
                                            onImage = { onAction(AppAction.OpenImage(id, it)) },
                                            cardOnly = planned.cardOnly,
                                            onAction = onAction,
                                            onRetry =
                                                if (
                                                    rowIndex == state.messages.lastIndex &&
                                                        !state.running &&
                                                        !state.syncing &&
                                                        !state.sending &&
                                                        !state.readOnly &&
                                                        state.inbox.none { !it.hidden } &&
                                                        row.noticeKind in
                                                            setOf(
                                                                "error",
                                                                "interrupted",
                                                                "max-tokens",
                                                            ) &&
                                                        row.notice?.code !in
                                                            setOf(
                                                                "MISSING_CREDENTIAL",
                                                                "AUTH",
                                                                "INVALID_CREDENTIAL",
                                                                "QUOTA",
                                                                "CLIENT_UPGRADE_REQUIRED",
                                                                "MODEL_UNSUPPORTED",
                                                                "UNSUPPORTED_REASONING_EFFORT",
                                                            ) &&
                                                        row.notice?.code?.startsWith("ACCOUNT_") !=
                                                            true
                                                ) {
                                                    {
                                                        onAction(
                                                            AppAction.RetryTranscriptNotice(
                                                                id,
                                                                row.key,
                                                            ),
                                                        )
                                                    }
                                                } else null,
                                        )
                                        row.tool?.let { tool ->
                                            val media = generatedMediaByCall[tool.callId]
                                            media?.let {
                                                dev.fughetta.dshcompose.ui.media.GeneratedMediaCard(
                                                    it.media,
                                                    id,
                                                    it.createdAt,
                                                    onAction = { action ->
                                                        onAction(AppAction.Media(action))
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                items(state.approvals, key = { "approval:${it.eventId}" }) { approval ->
                    ApprovalCard(id, state.syncing, approval, onAction)
                }
                items(state.questions, key = { "question:${it.request.eventId}" }) { question ->
                    QuestionCard(id, state.syncing, question, onAction)
                }
                item(key = "chat-tail", contentType = "tail") { Spacer(Modifier.height(1.dp)) }
            }
            if (
                state.messages.isEmpty() &&
                    !state.syncing &&
                    !state.hasMoreHistory &&
                    state.approvals.isEmpty() &&
                    state.questions.isEmpty()
            )
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    dev.fughetta.dshcompose.ui.transcript.WhaleTail(
                        Modifier.padding(bottom = 16.dp),
                        height = 40.dp,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        still = true,
                    )
                    Text(
                        stringResource(R.string.chat_empty_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.chat_empty_hint),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            if (showJump)
                FilledTonalButton(
                    onClick = jumpToLatest,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 4.dp),
                ) {
                    Icon(
                        painterResource(CoreDesignsystemR.drawable.ic_arrow_downward),
                        null,
                        Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.chat_jump_latest))
                }
        }
    }
}

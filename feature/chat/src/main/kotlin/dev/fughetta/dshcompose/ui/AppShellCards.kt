package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.messages.splitQuote
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun AgentPresetChip(
    id: String,
    current: String?,
    source: StateFlow<AgentPresetsUiState>,
    enabled: Boolean,
    switchable: Boolean,
    onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val catalog = source.collectAsStateWithLifecycle().value
    var open by rememberSaveable { mutableStateOf(false) }
    val presets = catalog.presets
    LaunchedEffect(Unit) { if (presets.isEmpty()) onAction(AppAction.RefreshAgentPresets) }
    // A draft with no pick starts with the Host's default.
    val shown = current ?: presets.firstOrNull { it.isDefault }?.id
    val label =
        shown?.let { value ->
            presets.firstOrNull { it.id == value }?.let(::probedPresetName) ?: value
        } ?: stringResource(CoreDesignsystemR.string.agent_presets)
    if (compact) {
        FilledTonalIconButton(
            onClick = {
                open = true
                onAction(AppAction.RefreshAgentPresets)
            },
            enabled = enabled,
            modifier = modifier,
        ) {
            Icon(
                painterResource(CoreDesignsystemR.drawable.ic_s_robot),
                stringResource(CoreDesignsystemR.string.agent_presets) + ": " + label,
            )
        }
    } else
        AssistChip(
            modifier = modifier,
            onClick = {
                open = true
                onAction(AppAction.RefreshAgentPresets)
            },
            enabled = enabled,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    painterResource(CoreDesignsystemR.drawable.ic_chevron_right),
                    null,
                    Modifier.size(AssistChipDefaults.IconSize).rotate(90f),
                )
            },
        )
    if (open)
        AgentPresetPickerDialog(
            catalog,
            shown,
            switchable,
            onConfirm = { preset ->
                open = false
                if (preset != shown || current == null)
                    onAction(AppAction.SelectAgentPreset(id, preset))
            },
            onDismiss = { open = false },
            onReload = { onAction(AppAction.RefreshAgentPresets) },
        )
}

@Composable
internal fun ApprovalCard(
    id: String,
    syncing: Boolean,
    approval: ApprovalUiState,
    onAction: (AppAction) -> Unit,
) {
    val language = LocalConfiguration.current.locales[0].language
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.approval_title, approval.toolName),
                style = MaterialTheme.typography.titleMedium,
            )
            val reason =
                approval.displayReason[language] ?: approval.displayReason["en"] ?: approval.reason
            reason?.let {
                Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.align(Alignment.End).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onAction(AppAction.RespondApproval(id, approval, false)) },
                    enabled = !syncing && !approval.responding,
                ) {
                    Text(stringResource(R.string.reject))
                }
                Button(
                    onClick = { onAction(AppAction.RespondApproval(id, approval, true)) },
                    enabled = !syncing && !approval.responding,
                ) {
                    Text(stringResource(R.string.allow_once))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BuiltInMessage(
    sessionId: String,
    row: ChatItem,
    referenceLabels: dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex,
    onMenu: () -> Unit,
    onImage: (dev.fughetta.dshcompose.domain.attachments.ChatAttachment) -> Unit,
    cardOnly: Boolean = false,
    onAction: (AppAction) -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val fromUser = row.role == ChatRole.User
    val longPress =
        Modifier.combinedClickable(
            onClick = {},
            onLongClickLabel = stringResource(R.string.message_actions_title),
            onLongClick = onMenu,
        )
    // Process steps (thinking, tool calls) read as flat folded lines; only messages get a bubble.
    if (!cardOnly)
        dev.fughetta.dshcompose.ui.transcript.TranscriptReasoning(
            sessionId,
            row.key,
            row.reasoning,
            row.streaming,
            longPress,
        )
    if (row.tool != null) {
        dev.fughetta.dshcompose.ui.transcript.TranscriptToolRow(
            row,
            onOpenFile = { path ->
                onAction(
                    AppAction.TranscriptArtifact(
                        sessionId,
                        dev.fughetta.dshcompose.domain.sessions.TranscriptArtifact(
                            path,
                            dev.fughetta.dshcompose.domain.sessions.TranscriptArtifactOrigin.Linked,
                            row.turnId.orEmpty(),
                        ),
                        dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactAction.Preview,
                    ),
                )
            },
            onOpenSubagent = { child ->
                onAction(
                    AppAction.Agents(
                        dev.fughetta.dshcompose.ui.agents.AgentsAction.Open(
                            dev.fughetta.dshcompose.domain.sessions.SubagentAddress(
                                sessionId,
                                child,
                            ),
                        ),
                    ),
                )
            },
            cardOnly = cardOnly,
            modifier = longPress,
        )
        if (row.interrupted && !cardOnly)
            Text(
                stringResource(R.string.turn_cancelled),
                style = MaterialTheme.typography.labelSmall,
            )
        return
    }
    if (
        !fromUser &&
            row.text.isEmpty() &&
            row.command == null &&
            row.noticeKind == null &&
            row.attachments.isEmpty() &&
            !row.interrupted
    ) {
        if (row.streaming && row.reasoning.isBlank())
            dev.fughetta.dshcompose.ui.transcript.WhaleTail(
                contentDescription = stringResource(R.string.thinking),
            )
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color =
                if (fromUser) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            modifier = (if (fromUser) Modifier.widthIn(max = 640.dp) else Modifier).then(longPress),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row.command?.let { command ->
                    Text(
                        command.line,
                        fontFamily =
                            dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (!command.done) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (row.text.isEmpty())
                        Text(
                            stringResource(
                                if (command.success) R.string.command_completed
                                else R.string.command_failed,
                            ),
                        )
                }
                if (row.command != null && row.text.isNotEmpty())
                    dev.fughetta.dshcompose.ui.transcript.TranscriptOutput(row.text)
                if (row.noticeKind != null)
                    dev.fughetta.dshcompose.ui.transcript.TranscriptNoticeRow(
                        row,
                        onCredentials = { onAction(AppAction.OpenProviderSettings) },
                        onModel = { onAction(AppAction.OpenModelPicker(sessionId)) },
                        onRetry = onRetry,
                    )
                if (row.text.isNotEmpty() && row.noticeKind == null && row.command == null) {
                    if (row.role == ChatRole.Assistant)
                        dev.fughetta.dshcompose.ui.markdown.MarkdownMessageText(
                            row.text,
                            referenceLabels,
                            copySource = false,
                            selectable = false,
                        )
                    else {
                        val quoted =
                            remember(row.text) {
                                dev.fughetta.dshcompose.domain.messages.splitQuote(row.text)
                            }
                        quoted.quote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (quoted.body.isNotEmpty())
                            MessageReferenceText(quoted.body, referenceLabels)
                    }
                }
                for (attachment in row.attachments) {
                    val label =
                        attachment.name
                            ?: stringResource(
                                if (attachment.kind == "image") CoreDesignsystemR.string.image
                                else R.string.file,
                            )
                    if (attachment.kind == "image" && attachment.attachmentId != null)
                        TextButton(onClick = { onImage(attachment) }) { Text(label) }
                    else Text(label, style = MaterialTheme.typography.labelLarge)
                }
                if (row.interrupted)
                    Text(
                        stringResource(R.string.turn_cancelled),
                        style = MaterialTheme.typography.labelSmall,
                    )
                if (row.streaming)
                    dev.fughetta.dshcompose.ui.transcript.WhaleTail(
                        contentDescription = stringResource(R.string.thinking),
                    )
            }
        }
    }
}

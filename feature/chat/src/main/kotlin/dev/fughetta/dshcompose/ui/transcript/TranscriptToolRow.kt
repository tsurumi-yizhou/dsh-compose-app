package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.TranscriptFileChange
import dev.fughetta.dshcompose.domain.transcript.*
import dev.fughetta.dshcompose.domain.transcript.ToolPresentationView
import dev.fughetta.dshcompose.domain.transcript.TranscriptDiffKind
import dev.fughetta.dshcompose.domain.transcript.TranscriptDiffLine
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont
import dev.fughetta.dshcompose.ui.dynamicui.DynamicUiCard
import dev.fughetta.dshcompose.ui.dynamicui.genuiToolSource
import dev.fughetta.dshcompose.ui.dynamicui.genuiToolView
import dev.fughetta.dshcompose.ui.markdown.CodeRows
import dev.fughetta.dshcompose.ui.markdown.MarkdownBlock
import dev.fughetta.dshcompose.ui.nativeplugins.NativePluginView
import dev.fughetta.dshcompose.ui.nativeplugins.NativePluginViewBudget
import dev.fughetta.dshcompose.ui.toUiJson
import dev.fughetta.dshcompose.ui.toUiPayload
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun TranscriptToolRow(
    row: ChatItem,
    onOpenFile: (String) -> Unit,
    onOpenSubagent: (String) -> Unit,
    cardOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tool = row.tool ?: return
    val holder = remember(row.key) { arrayOfNulls<ToolPresentationView>(1) }
    val view =
        remember(row) {
            val previous = holder[0]
            val tool = checkNotNull(row.tool)
            val arguments =
                if (
                    previous != null &&
                        previous.tool.arguments == tool.arguments &&
                        (previous.tool.state == ToolState.Streaming) ==
                            (tool.state == ToolState.Streaming)
                )
                    previous.arguments
                else if (tool.state == ToolState.Streaming) null
                else
                    runCatching {
                        Json.parseToJsonElement(tool.arguments).toUiPayload() as? ToolPayload.Obj
                    }
                        .getOrNull()
            ToolPresentationView(row, arguments).also { holder[0] = it }
        }
    val prettyArguments by
        remember(view) {
            lazy {
                view.arguments?.let {
                    Json { prettyPrint = true }
                        .encodeToString(JsonElement.serializer(), it.toUiJson())
                } ?: view.tool.arguments
            }
        }
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    var showDiff by rememberSaveable(row.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!cardOnly)
            TextButton(
                { expanded = !expanded },
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                val verb =
                    when (tool.name) {
                        "bash",
                        "pwsh",
                        "job_output" -> R.string.transcript_run
                        "read",
                        "read_image",
                        "parse_document",
                        "ocr" -> R.string.transcript_read
                        "write" -> R.string.transcript_write
                        "edit",
                        "str_replace_editor" -> R.string.transcript_edit
                        "grep",
                        "glob" -> R.string.transcript_search
                        "web_fetch",
                        "web_search",
                        "deep_research" -> R.string.transcript_web
                        "subagent",
                        "subagent_fork",
                        "send_message",
                        "interrupt_agent" -> R.string.transcript_agent
                        else -> R.string.transcript_call
                    }
                Text(stringResource(verb), style = MaterialTheme.typography.labelLarge)
                Text(
                    " · ${view.summary}",
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily =
                        if (view.fileTool || view.terminal) LocalAppearanceCodeFont.current
                        else null,
                )
                if (tool.state in setOf(ToolState.Streaming, ToolState.Running))
                    WhaleTail(
                        height = 16.dp,
                        contentDescription =
                            stringResource(
                                if (tool.state == ToolState.Streaming) R.string.tool_streaming
                                else R.string.tool_running,
                            ),
                    )
                else
                    Text(
                        stringResource(
                            if (view.sandboxBlocked) R.string.transcript_authorization
                            else
                                when (tool.state) {
                                    ToolState.AwaitingApproval -> R.string.tool_approval
                                    ToolState.Failed -> R.string.tool_failed
                                    ToolState.Denied -> R.string.tool_denied
                                    ToolState.Cancelled -> R.string.tool_cancelled
                                    else -> R.string.tool_succeeded
                                },
                        ),
                        color =
                            if (tool.state == ToolState.Failed && !view.sandboxBlocked)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
        if (tool.state == ToolState.Succeeded)
            (tool.meta?.toUiJson() as? JsonObject)?.get("dsh-compose/view")?.let {
                NativePluginView(
                    it,
                    JsonNull,
                    enabled = false,
                    onAction = {},
                    budget = NativePluginViewBudget.ToolCard,
                )
            }
        val genui =
            remember(row) {
                genuiToolSource(row)?.let { source ->
                    genuiToolView(row)?.takeIf { it.view != null }?.let { source to it }
                }
            }
        genui?.let { (source, parsed) -> DynamicUiCard(source, true, parsed) }
        if (expanded && !cardOnly) {
            ResearchSourceLinks(tool.meta)
            view.path?.let { path ->
                TextButton({ onOpenFile(path) }) {
                    Text(
                        path,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = LocalAppearanceCodeFont.current,
                    )
                }
            }
            val agent =
                remember(row) {
                    when (tool.name) {
                        "subagent",
                        "subagent_fork" ->
                            (tool.meta?.toUiJson() as? JsonObject)
                                ?.get("subagentId")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: Regex("started subagent ([\\w:.-]+)")
                                    .find(row.text)
                                    ?.groupValues
                                    ?.get(1)
                        "send_message",
                        "interrupt_agent" -> view.arg("agent_id")
                        else -> null
                    }
                }
            agent?.let {
                TextButton({ onOpenSubagent(it) }) {
                    Text(stringResource(R.string.transcript_open_agent))
                }
            }
            if (!view.fileTool || view.path == null) {
                Text(
                    stringResource(R.string.transcript_arguments),
                    style = MaterialTheme.typography.labelSmall,
                )
                CodeRows(MarkdownBlock.Code(prettyArguments, "json"))
            }
            val read = view.read
            if (read != null) {
                Text(
                    "${read.firstLine}–${read.firstLine+read.code.count {it=='\n'}}",
                    style = MaterialTheme.typography.labelSmall,
                )
                CodeRows(
                    MarkdownBlock.Code(read.code, read.language.orEmpty()),
                    firstLine = read.firstLine,
                )
            } else if (view.written != null) CodeRows(MarkdownBlock.Code(view.written!!, ""))
            if (view.changes != null || looksLikeTranscriptDiff(row.text))
                TextButton({ showDiff = true }) {
                    Text(stringResource(R.string.transcript_full_diff))
                }
            if (row.text.isNotEmpty() && read == null) {
                Text(
                    stringResource(R.string.transcript_output),
                    style = MaterialTheme.typography.labelSmall,
                )
                TranscriptOutput(row.text)
            } else view.failureText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showDiff) TranscriptDiffDialog(view) { showDiff = false }
}

@Composable
private fun ResearchSourceLinks(meta: ToolPayload?) {
    val sources =
        remember(meta) {
            (meta as? ToolPayload.Obj)
                ?.arrayItems("sources")
                ?.mapNotNull { value ->
                    val source = value as? ToolPayload.Obj ?: return@mapNotNull null
                    val url = source.text("url") ?: return@mapNotNull null
                    if (!url.startsWith("https://") && !url.startsWith("http://"))
                        return@mapNotNull null
                    (source.text("title")?.takeIf { it.isNotBlank() } ?: url) to url
                }
                ?.distinctBy { it.second }
                .orEmpty()
        }
    val uriHandler = LocalUriHandler.current
    sources.forEach { (title, url) ->
        TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun TranscriptOutput(text: String, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TranscriptAnsiLayout?>(null) }
    LaunchedEffect(text) {
        layout = withContext(Dispatchers.Default) { TranscriptAnsiLayout(text) }
    }
    val colors = MaterialTheme.colorScheme
    val palette =
        remember(colors) {
            listOf(
                colors.onSurface,
                colors.error,
                colors.tertiary,
                colors.secondary,
                colors.primary,
                Color(0xFFA36FC2),
                Color(0xFF3F9EAD),
                colors.onSurfaceVariant,
                colors.outline,
                colors.error,
                colors.tertiary,
                colors.secondary,
                colors.primary,
                Color(0xFFC78EE0),
                Color(0xFF65C8D2),
                colors.onSurface,
            )
        }
    val rows = layout
    if (rows == null) {
        LinearProgressIndicator(modifier.fillMaxWidth())
        return
    }
    val density = LocalDensity.current
    val style =
        MaterialTheme.typography.bodySmall.copy(fontFamily = LocalAppearanceCodeFont.current)
    val measurer = rememberTextMeasurer()
    val width = with(density) { (measurer.measure("M", style).size.width * rows.columns).toDp() }
    SelectionContainer {
        Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(maxOf(280.dp, width)).heightIn(max = 280.dp)) {
                items(rows.size) { index ->
                    val line = remember(rows, index, palette) { rows.line(index, palette) }
                    Text(
                        line,
                        fontFamily = LocalAppearanceCodeFont.current,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptDiffDialog(view: ToolPresentationView, onDismiss: () -> Unit) {
    val blocks = view.changes
    if (blocks != null) {
        TranscriptChangesDialog(
            listOf(
                TranscriptFileChange(
                    view.path.orEmpty(),
                    kotlinx.collections.immutable.persistentListOf(view.tool.callId),
                    blocks.toPersistentList(),
                ),
            ),
            onDismiss = onDismiss,
        )
        return
    }
    var wrap by remember { mutableStateOf(true) }
    var diff by remember(view) { mutableStateOf<List<TranscriptDiffLine>?>(null) }
    LaunchedEffect(view) { diff = withContext(Dispatchers.Default) { view.diff } }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text(
                        stringResource(R.string.transcript_diff),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    FilterChip(
                        wrap,
                        { wrap = !wrap },
                        label = { Text(stringResource(R.string.transcript_wrap)) },
                    )
                    TranscriptCopyButton(view.item.text)
                    TextButton(onDismiss) { Text(stringResource(R.string.transcript_close)) }
                }
                val lines = diff
                if (lines == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else TranscriptDiffLines(lines, Modifier.weight(1f), wrap)
            }
        }
    }
}

@Composable
fun TranscriptDiffLines(
    lines: List<TranscriptDiffLine>,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
) {
    var layout by remember(lines) { mutableStateOf<TranscriptDiffLayout?>(null) }
    LaunchedEffect(lines) {
        layout = withContext(Dispatchers.Default) { TranscriptDiffLayout(lines) }
    }
    val data = layout
    if (data == null) {
        LinearProgressIndicator(modifier.fillMaxWidth())
        return
    }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val style =
        MaterialTheme.typography.bodySmall.copy(fontFamily = LocalAppearanceCodeFont.current)
    val measurer = rememberTextMeasurer()
    val cell = measurer.measure("M", style).size.width
    val gutter = with(density) { (cell * (data.digits * 2 + 3)).toDp() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width =
            if (wrap) maxWidth
            else maxOf(maxWidth, with(density) { (cell * data.columns).toDp() } + gutter + 16.dp)
        Box(
            if (wrap) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                LazyColumn(Modifier.width(width).fillMaxHeight()) {
                    items(data.parts.size) { index ->
                        val part = data.parts[index]
                        val line = part.line
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    when (line.kind) {
                                        TranscriptDiffKind.Added -> colors.tertiaryContainer
                                        TranscriptDiffKind.Removed -> colors.errorContainer
                                        TranscriptDiffKind.Hunk -> colors.secondaryContainer
                                        else -> Color.Transparent
                                    },
                                )
                                .padding(vertical = 2.dp),
                        ) {
                            androidx.compose.foundation.text.selection.DisableSelection {
                                Text(
                                    if (part.continuation) "↪"
                                    else
                                        "${line.oldLine?.toString().orEmpty()} ${line.newLine?.toString().orEmpty()}",
                                    Modifier.width(gutter).padding(horizontal = 4.dp),
                                    style = style,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Text(
                                line.text.substring(part.start, part.end),
                                Modifier.weight(1f).padding(end = 8.dp),
                                style = style,
                                softWrap = wrap,
                            )
                        }
                    }
                }
            }
        }
    }
}

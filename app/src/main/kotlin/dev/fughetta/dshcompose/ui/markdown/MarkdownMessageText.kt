package dev.fughetta.dshcompose.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.links.ReplyLinkTarget
import dev.fughetta.dshcompose.domain.links.bareDomainLink
import dev.fughetta.dshcompose.domain.links.replyLinkTarget
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.dynamicui.DynamicUiCard
import dev.fughetta.dshcompose.ui.dynamicui.LocalDynamicUi
import dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings
import dev.fughetta.dshcompose.ui.phone.performanceContentSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalReplyLinkOpen =
    staticCompositionLocalOf<((String, Long, String) -> Unit)?> { null }

@Composable
internal fun MarkdownMessageText(
    text: String,
    labels: ReferenceLabelIndex,
    copySource: Boolean = true,
    // Off in transcript bubbles, where long press opens the message menu (which offers selection).
    selectable: Boolean = true,
) {
    val selection: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (selectable) SelectionContainer { content() } else content()
    }
    val allowBareViews = LocalDynamicUi.current.allowViews
    val latestText by rememberUpdatedState(text)
    var document by remember(labels, allowBareViews) { mutableStateOf<MarkdownDocument?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    val images = LocalReplyImages.current
    val openLink = LocalReplyLinkOpen.current
    var gallery by
        remember(images.generation, images.sessionId) {
            mutableStateOf<ReplyGallerySelection?>(null)
        }
    LaunchedEffect(labels, allowBareViews) {
        // Conflate between parses, not before text reaches the transcript. The final input is
        // retained.
        snapshotFlow { latestText }
            .conflate()
            .collect { source ->
                document =
                    withContext(Dispatchers.Default) {
                        MarkdownDocuments.parse(source, labels, allowBareViews)
                    }
                delay(100)
            }
    }
    val visibleImages = remember { mutableStateMapOf<Int, Unit>() }
    val parsed = document
    if (parsed == null) selection { Text(text, style = MaterialTheme.typography.bodyLarge) }
    else
        CompositionLocalProvider(
            LocalReplyGalleryRegistration provides
                { index, mounted ->
                    if (mounted) visibleImages[index] = Unit else visibleImages.remove(index)
                },
            LocalReplyGalleryOpen provides
                { index ->
                    gallery = replyGallerySelection(parsed.images, visibleImages.keys, index)
                },
        ) {
            selection {
                MarkdownBlocks(
                    parsed.blocks,
                    onLink = { target ->
                        if (openLink != null && images.sessionId.isNotBlank())
                            openLink(images.sessionId, images.generation, target)
                        else link = target
                    },
                )
            }
        }
    gallery?.let { ReplyImageGallery(it) { gallery = null } }
    if (copySource) CopyTextButton(text, R.string.copy_message_source)
    link?.let { target ->
        val uriHandler = LocalUriHandler.current
        val external =
            remember(target) {
                (replyLinkTarget(target) as? ReplyLinkTarget.External)?.address
                    ?: bareDomainLink(target)
            }
        val allowed = external != null
        var error by remember(target) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text(stringResource(R.string.markdown_open_link)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text(external ?: target) }
                    if (!allowed || error)
                        Text(
                            stringResource(
                                if (error) R.string.markdown_link_failed
                                else R.string.markdown_link_unavailable,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = allowed,
                    onClick = {
                        try {
                            uriHandler.openUri(external ?: return@TextButton)
                            link = null
                        } catch (_: IllegalArgumentException) {
                            error = true
                        } catch (_: SecurityException) {
                            error = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { link = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

internal fun externalMarkdownLink(target: String): Boolean =
    replyLinkTarget(target) is ReplyLinkTarget.External

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownBlock>, onLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) when (block) {
            is MarkdownBlock.Text -> {
                val style =
                    when (block.heading) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        4,
                        5,
                        6 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }
                MarkdownContent(block.content, onLink, style = style)
            }
            is MarkdownBlock.Disclosure -> MarkdownDisclosure(block, onLink)
            is MarkdownBlock.Code -> MarkdownCode(block)
            is MarkdownBlock.Visual -> {
                val context = LocalDynamicUi.current
                if (!context.allowViews) MarkdownCode(MarkdownBlock.Code(block.source, "dsh-ui"))
                else
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DynamicUiCard(block.source, block.closed, block.parsed)
                        var source by
                            remember(block.source, block.closed) {
                                mutableStateOf(block.closed && block.parsed?.view == null)
                            }
                        LaunchedEffect(block.closed, context.streaming) {
                            if (!block.closed && !context.streaming) source = true
                        }
                        TextButton(onClick = { source = !source }) {
                            Text(
                                stringResource(
                                    if (source) R.string.view_hide_source else R.string.view_source,
                                ),
                            )
                        }
                        if (source) MarkdownCode(MarkdownBlock.Code(block.source, "json"))
                    }
            }
            is MarkdownBlock.Math -> MathDisplay(block.formula)
            is MarkdownBlock.Quote -> {
                val border = MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier.fillMaxWidth()
                        .drawBehind {
                            drawLine(border, Offset.Zero, Offset(0f, size.height), 3.dp.toPx())
                        }
                        .padding(start = 12.dp),
                ) {
                    MarkdownBlocks(block.blocks, onLink)
                }
            }
            is MarkdownBlock.ListEntry ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        block.marker,
                        Modifier.widthIn(min = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Box(Modifier.weight(1f)) { MarkdownBlocks(block.blocks, onLink) }
                }
            is MarkdownBlock.Table -> MarkdownTable(block, onLink)
            MarkdownBlock.Rule -> HorizontalDivider()
        }
    }
}

@Composable
private fun MarkdownDisclosure(block: MarkdownBlock.Disclosure, onLink: (String) -> Unit) {
    var expanded by rememberSaveable(block.position) { mutableStateOf(block.initiallyOpen) }
    val description =
        stringResource(
            if (expanded) R.string.markdown_details_expanded
            else R.string.markdown_details_collapsed,
        )
    Column(
        Modifier.fillMaxWidth().performanceContentSize(LocalPerformanceSettings.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = description }
                .padding(vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            DisableSelection {
                Text(
                    if (expanded) "▾" else "▸",
                    Modifier.width(24.dp).clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.weight(1f)) {
                if (block.title == null || block.title.text.isBlank())
                    Text(
                        stringResource(R.string.markdown_details),
                        style = MaterialTheme.typography.titleSmall,
                    )
                else
                    MarkdownContent(
                        block.title,
                        onLink,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
            }
        }
        if (expanded) Box(Modifier.padding(start = 24.dp)) { MarkdownBlocks(block.blocks, onLink) }
    }
}

@Composable
internal fun MarkdownCode(code: MarkdownBlock.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    code.language.ifBlank { stringResource(R.string.markdown_code) },
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
                CopyTextButton(code.text, R.string.markdown_copy_code)
            }
            CodeRows(code)
        }
    }
}

@Composable
internal fun MarkdownTable(table: MarkdownBlock.Table, onLink: (String) -> Unit) {
    val columnCount = remember(table) { table.rows.maxOfOrNull { it.size } ?: 0 }
    if (columnCount == 0) return
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(180.dp * columnCount).heightIn(max = 360.dp)) {
            items(table.rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    for (cell in row) Box(
                        Modifier.width(180.dp)
                            .background(
                                if (cell.header) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            )
                            .padding(10.dp),
                    ) {
                        MarkdownContent(
                            cell.content,
                            onLink,
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (cell.header) FontWeight.Bold else FontWeight.Normal,
                            textAlign =
                                when (cell.alignment) {
                                    TableAlignment.Left -> TextAlign.Start
                                    TableAlignment.Center -> TextAlign.Center
                                    TableAlignment.Right -> TextAlign.End
                                },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MarkdownContent(
    content: MarkdownText,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    val annotated = markdownAnnotated(content, onLink)
    val images =
        remember(content) {
            content.marks
                .filter { it.kind == MarkdownMarkKind.Image || it.formula?.display == true }
                .sortedBy { it.start }
        }
    val formulas =
        remember(content) {
            content.marks
                .filter { it.kind == MarkdownMarkKind.Math && it.formula?.display == false }
                .sortedBy { it.start }
        }
    if (images.isEmpty())
        MathTextRun(annotated, formulas, 0, style, modifier, fontWeight, textAlign)
    else
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var previous = 0
            for (image in images) {
                if (image.start > previous)
                    MathTextRun(
                        annotated.subSequence(previous, image.start),
                        formulas.filter { it.start >= previous && it.end <= image.start },
                        previous,
                        style,
                        fontWeight = fontWeight,
                        textAlign = textAlign,
                    )
                if (image.kind == MarkdownMarkKind.Math)
                    image.formula?.let { MathDisplay(it, style) }
                else
                    image.target?.let {
                        ReplyImage(
                            it,
                            content.text.substring(image.start, image.end),
                            image.imageIndex ?: -1,
                            image.imageSize,
                        )
                    }
                previous = image.end
            }
            if (previous < annotated.length)
                MathTextRun(
                    annotated.subSequence(previous, annotated.length),
                    formulas.filter { it.start >= previous },
                    previous,
                    style,
                    fontWeight = fontWeight,
                    textAlign = textAlign,
                )
        }
}

@Composable
private fun markdownAnnotated(content: MarkdownText, onLink: (String) -> Unit): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val codeFont = dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current
    return remember(content, colors, onLink, codeFont) {
        AnnotatedString.Builder(content.text)
            .apply {
                for (mark in content.marks) {
                    if (mark.kind == MarkdownMarkKind.Image) continue
                    if (mark.kind == MarkdownMarkKind.Link) {
                        mark.target?.let { target ->
                            addLink(
                                LinkAnnotation.Clickable(
                                    target,
                                    TextLinkStyles(
                                        style =
                                            SpanStyle(
                                                color = colors.primary,
                                                textDecoration = TextDecoration.Underline,
                                            ),
                                    ),
                                    linkInteractionListener = { onLink(target) },
                                ),
                                mark.start,
                                mark.end,
                            )
                        }
                    } else
                        addStyle(
                            when (mark.kind) {
                                MarkdownMarkKind.Strong -> SpanStyle(fontWeight = FontWeight.Bold)
                                MarkdownMarkKind.Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
                                MarkdownMarkKind.Strike ->
                                    SpanStyle(textDecoration = TextDecoration.LineThrough)
                                MarkdownMarkKind.Underline ->
                                    SpanStyle(textDecoration = TextDecoration.Underline)
                                MarkdownMarkKind.Highlight ->
                                    SpanStyle(
                                        background = colors.tertiaryContainer,
                                        color = colors.onTertiaryContainer,
                                    )
                                MarkdownMarkKind.Superscript ->
                                    SpanStyle(
                                        baselineShift = BaselineShift.Superscript,
                                        fontSize = 0.8.em,
                                    )
                                MarkdownMarkKind.Subscript ->
                                    SpanStyle(
                                        baselineShift = BaselineShift.Subscript,
                                        fontSize = 0.8.em,
                                    )
                                MarkdownMarkKind.Code ->
                                    SpanStyle(
                                        fontFamily = codeFont,
                                        background = colors.surfaceContainerHigh,
                                    )
                                MarkdownMarkKind.Reference ->
                                    SpanStyle(
                                        fontWeight = FontWeight.Medium,
                                        background = colors.secondaryContainer,
                                        color = colors.onSecondaryContainer,
                                    )
                                MarkdownMarkKind.Math ->
                                    SpanStyle(
                                        fontFamily = codeFont,
                                        color = colors.onSurfaceVariant,
                                        fontSize = 0.875.em,
                                    )
                                MarkdownMarkKind.Link -> error("handled above")
                                MarkdownMarkKind.Image -> error("handled above")
                            },
                            mark.start,
                            mark.end,
                        )
                }
            }
            .toAnnotatedString()
    }
}

@Composable
private fun CopyTextButton(text: String, label: Int) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) }
        },
    ) {
        Text(stringResource(label))
    }
}

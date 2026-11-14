package dev.fughetta.dshcompose.ui.dynamicui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.markdown.*
import dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings
import dev.fughetta.dshcompose.ui.phone.performanceContentSize
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch

private val LocalUiPictureIndices = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

/**
 * Each card owns its image indices; hidden pages and folded panels never enter the visible gallery.
 */
@Composable
internal fun UiCardPictures(view: UiView, content: @Composable () -> Unit) {
    val context = LocalReplyImages.current
    val pictures =
        remember(view) {
            view.root.walk().filterIsInstance<UiPicture>().filter { it.source != null }.toList()
        }
    val indices =
        remember(pictures) {
            pictures.mapIndexed { index, picture -> picture.path to index }.toMap()
        }
    val images =
        remember(pictures) {
            pictures.map { MarkdownGalleryImage(it.source!!, it.alt) }.toPersistentList()
        }
    val visible =
        remember(view, context.generation, context.sessionId) { mutableStateMapOf<Int, Unit>() }
    var selection by
        remember(view, context.generation, context.sessionId) {
            mutableStateOf<ReplyGallerySelection?>(null)
        }
    CompositionLocalProvider(
        LocalUiPictureIndices provides indices,
        LocalReplyGalleryRegistration provides
            { index, mounted ->
                if (mounted) visible[index] = Unit else visible.remove(index)
            },
        LocalReplyGalleryOpen provides
            { index ->
                selection = replyGallerySelection(images, visible.keys, index)
            },
    ) {
        content()
    }
    selection?.let { ReplyImageGallery(it) { selection = null } }
}

@Composable
internal fun RestrictedTextView(
    rich: UiRichText,
    appearance: UiTextStyle = UiTextStyle(),
    muted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val codeFont = dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current
    val annotated =
        remember(rich, colors, codeFont) {
            buildAnnotatedString {
                append(rich.text)
                for (span in rich.spans) addStyle(
                    if (span.code)
                        SpanStyle(fontFamily = codeFont, background = colors.surfaceContainerHigh)
                    else SpanStyle(fontWeight = FontWeight.Bold),
                    span.start,
                    span.end,
                )
            }
        }
    var style =
        when (appearance.size) {
            UiTextSize.Body,
            UiTextSize.Mono -> MaterialTheme.typography.bodyLarge
            UiTextSize.Caption -> MaterialTheme.typography.bodySmall
            UiTextSize.Heading -> MaterialTheme.typography.titleMedium
            UiTextSize.Display -> MaterialTheme.typography.headlineSmall
        }
    if (appearance.size == UiTextSize.Mono)
        style =
            style.copy(
                fontFamily = dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current,
            )
    if (appearance.bold) style = style.copy(fontWeight = FontWeight.SemiBold)
    Text(
        annotated,
        modifier.then(
            if (appearance.size in setOf(UiTextSize.Heading, UiTextSize.Display))
                Modifier.semantics { heading() }
            else Modifier,
        ),
        color = if (muted) colors.onSurfaceVariant else toneColor(appearance.tone),
        style = style,
        textAlign =
            when (appearance.align) {
                UiTextAlign.Start -> TextAlign.Start
                UiTextAlign.Center -> TextAlign.Center
                UiTextAlign.End -> TextAlign.End
            },
    )
}

@Composable
internal fun UiTrend(points: List<Double?>, tone: UiTone) {
    val scale = remember(points) { chartScale(points) }
    val color = toneColor(tone)
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val path = Path()
        var connected = false
        for ((index, value) in points.withIndex()) {
            if (value == null) {
                connected = false
                continue
            }
            val x = index.toFloat() / (points.size - 1) * size.width
            val y = (1 - scale.fraction(value)) * size.height
            if (connected) path.lineTo(x, y) else path.moveTo(x, y)
            connected = true
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GenuiContent(node: UiNode, child: @Composable (UiNode) -> Unit) {
    when (node) {
        is UiPanel -> {
            val base = MaterialTheme.colorScheme.surfaceContainer
            val tint =
                node.tint?.let { Color((0xff000000L or it).toInt()) }
                    ?: node.tone?.let { toneColor(it) }
            Surface(
                color =
                    if (tint == null) base
                    else androidx.compose.ui.graphics.lerp(base, tint, 0.10f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    node.children.forEach { child(it) }
                }
            }
        }
        is UiGrid ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns =
                    node.columns.coerceAtMost((maxWidth.value / 140f).toInt().coerceAtLeast(1))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (row in node.children.chunked(columns)) Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for (item in row) Box(Modifier.weight(1f)) { child(item) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        is UiBrowse -> Browse(node, child)
        is UiList ->
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(node.items) { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (node.timeline) Text("•", color = MaterialTheme.colorScheme.primary)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RestrictedTextView(item.title)
                            item.subtitle?.let {
                                RestrictedTextView(it, UiTextStyle(UiTextSize.Caption), true)
                            }
                        }
                    }
                }
            }
        is UiTable -> {
            val table =
                remember(node) {
                    fun cell(value: UiRichText, header: Boolean) =
                        MarkdownCell(
                            MarkdownText(
                                value.text,
                                value.spans
                                    .map {
                                        MarkdownMark(
                                            it.start,
                                            it.end,
                                            if (it.code) MarkdownMarkKind.Code
                                            else MarkdownMarkKind.Strong,
                                        )
                                    }
                                    .toPersistentList(),
                            ),
                            header,
                            TableAlignment.Left,
                        )
                    MarkdownBlock.Table(
                        (listOf(node.columns.map { cell(it, true) }.toPersistentList()) +
                                node.rows.map { row ->
                                    row.map { cell(it, false) }.toPersistentList()
                                })
                            .toPersistentList(),
                    )
                }
            MarkdownTable(table) {}
        }
        is UiProgress ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                node.label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                if (node.ring)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { node.fraction },
                            modifier = Modifier.size(64.dp),
                        )
                        Text(
                            "${(node.fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                else
                    LinearProgressIndicator(
                        progress = { node.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                node.caption?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        is UiCode -> MarkdownCode(MarkdownBlock.Code(node.code, node.language))
        is UiPicture ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val index = LocalUiPictureIndices.current[node.path]
                if (node.source != null && index != null)
                    ReplyImage(node.source, node.alt, index, aspect = node.aspect)
                else {
                    if (node.alt.isNotEmpty()) Text(node.alt)
                    Text(
                        stringResource(R.string.markdown_image_failed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                node.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        is UiTag ->
            Surface(
                color = toneColor(node.tone).copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    node.text,
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = toneColor(node.tone),
                )
            }
        is UiWebOnly -> {
            val kind =
                stringResource(
                    when (node.kind) {
                        UiWebKind.Interactive -> R.string.view_kind_interactive
                        UiWebKind.Chart -> R.string.view_kind_chart
                        UiWebKind.Diagram -> R.string.view_kind_diagram
                        UiWebKind.Image -> R.string.view_kind_image
                        UiWebKind.Audio -> R.string.view_kind_audio
                        UiWebKind.Video -> R.string.view_kind_video
                        UiWebKind.Scene -> R.string.view_kind_scene
                        UiWebKind.Files -> R.string.view_kind_files
                        UiWebKind.Map -> R.string.view_kind_map
                        UiWebKind.Other -> R.string.view_kind_other
                    },
                )
            Text(
                stringResource(R.string.view_web_part, kind),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        is UiDivider -> HorizontalDivider()
        is UiSpacer -> Spacer(Modifier.height(12.dp))
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Browse(node: UiBrowse, child: @Composable (UiNode) -> Unit) {
    when (node.kind) {
        UiBrowseKind.Tabs -> {
            var selected by remember(node) { mutableIntStateOf(0) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryScrollableTabRow(selectedTabIndex = selected) {
                    node.pages.forEachIndexed { index, page ->
                        Tab(
                            selected == index,
                            onClick = { selected = index },
                            text = { Text(page.label) },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    node.pages[selected].children.forEach { child(it) }
                }
            }
        }
        UiBrowseKind.Accordion -> {
            var expanded by remember(node) { mutableStateOf(emptySet<Int>()) }
            Column(
                Modifier.performanceContentSize(LocalPerformanceSettings.current),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((index, page) in node.pages.withIndex()) {
                    val open = index in expanded
                    val state =
                        stringResource(
                            if (open) R.string.markdown_details_expanded
                            else R.string.markdown_details_collapsed,
                        )
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) {
                                expanded = if (open) expanded - index else expanded + index
                            }
                            .semantics { stateDescription = state }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(if (open) "▾" else "▸")
                        Text(page.label, style = MaterialTheme.typography.titleSmall)
                    }
                    if (open)
                        Column(
                            Modifier.padding(start = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            page.children.forEach { child(it) }
                        }
                }
            }
        }
        UiBrowseKind.Carousel -> {
            val animatePages =
                dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings.current.animations
            val pager = rememberPagerState { node.pages.size }
            val scope = rememberCoroutineScope()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalPager(pager, Modifier.fillMaxWidth(), beyondViewportPageCount = 0) { index
                    ->
                    if (index == pager.settledPage)
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            node.pages[index].children.forEach { child(it) }
                        }
                    else
                        Box(
                            Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                }
                Text(
                    node.pages[pager.settledPage].label,
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (animatePages) pager.animateScrollToPage(pager.settledPage - 1)
                                else pager.scrollToPage(pager.settledPage - 1)
                            }
                        },
                        enabled = pager.settledPage > 0,
                    ) {
                        Text(stringResource(R.string.view_page_previous))
                    }
                    Text(
                        stringResource(
                            R.string.view_page_count,
                            pager.settledPage + 1,
                            node.pages.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (animatePages) pager.animateScrollToPage(pager.settledPage + 1)
                                else pager.scrollToPage(pager.settledPage + 1)
                            }
                        },
                        enabled = pager.settledPage < node.pages.lastIndex,
                    ) {
                        Text(stringResource(R.string.view_page_next))
                    }
                }
            }
        }
    }
}

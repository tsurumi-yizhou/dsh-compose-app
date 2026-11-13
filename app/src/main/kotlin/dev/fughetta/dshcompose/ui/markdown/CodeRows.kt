package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
internal fun CodeRows(code: MarkdownBlock.Code, firstLine: Int? = null) {
    val latest by rememberUpdatedState(code)
    val highlighter = LocalCodeHighlighter.current
    var layout by remember { mutableStateOf<CodeLayout?>(null) }
    LaunchedEffect(highlighter) {
        snapshotFlow { latest }
            .conflate()
            .collect { input ->
                val plain = withContext(Dispatchers.Default) { CodeLayout.plain(input.text) }
                layout = plain
                highlighter
                    ?.tokens(input.text, input.language)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { layout = plain.coloured(it) }
                delay(100)
            }
    }
    val rendered = layout
    if (rendered == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val density = LocalDensity.current
    val style =
        MaterialTheme.typography.bodyMedium.copy(
            fontFamily = dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current,
        )
    val measurer = rememberTextMeasurer()
    val cell = remember(style, density) { measurer.measure("M", style, maxLines = 1).size.width }
    val textWidth = with(density) { (rendered.columns * cell).toDp() }
    val gutter =
        with(density) {
            (cell *
                    (if (firstLine == null) 2
                    else (firstLine + rendered.rowCount).toString().length + 2))
                .toDp()
        }
    val lineHeight = with(density) { style.lineHeight.toDp() }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, textWidth + gutter + 8.dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(width).heightIn(max = 360.dp)) {
                items(rendered.rowCount, key = { it }, contentType = { "code-line" }) { row ->
                    val annotated =
                        remember(rendered, row, colors) {
                            AnnotatedString.Builder(rendered.text(row))
                                .apply {
                                    for (token in rendered.tokens(row)) {
                                        val appearance =
                                            when (token.scope.substringBefore('.')) {
                                                "keyword",
                                                "operator",
                                                "selector-tag" -> SpanStyle(color = colors.primary)
                                                "string",
                                                "regexp",
                                                "symbol",
                                                "addition" -> SpanStyle(color = colors.tertiary)
                                                "number",
                                                "literal",
                                                "attr",
                                                "attribute",
                                                "variable",
                                                "meta",
                                                "type",
                                                "built_in" -> SpanStyle(color = colors.secondary)
                                                "comment",
                                                "doctag" ->
                                                    SpanStyle(
                                                        color = colors.onSurfaceVariant,
                                                        fontStyle = FontStyle.Italic,
                                                    )
                                                "title",
                                                "section" ->
                                                    SpanStyle(
                                                        color = colors.primary,
                                                        fontWeight = FontWeight.Medium,
                                                    )
                                                "deletion" -> SpanStyle(color = colors.error)
                                                else -> null
                                            }
                                        if (appearance != null)
                                            addStyle(appearance, token.start, token.end)
                                    }
                                }
                                .toAnnotatedString()
                        }
                    Row {
                        DisableSelection {
                            Text(
                                if (rendered.continuation(row)) "↪"
                                else
                                    firstLine
                                        ?.let { (it + rendered.logicalLine(row)).toString() }
                                        .orEmpty(),
                                Modifier.width(gutter).clearAndSetSemantics {},
                                color = colors.onSurfaceVariant,
                                style = style,
                            )
                        }
                        Text(
                            annotated,
                            Modifier.width(width - gutter).heightIn(min = lineHeight),
                            style = style,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

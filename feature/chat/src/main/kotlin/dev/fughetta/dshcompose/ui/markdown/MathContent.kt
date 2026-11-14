package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The measurer and its bounded cache live only as long as this visible text run. */
@Composable
private fun measuredFormulas(
    formulas: List<MarkdownFormula>,
    style: TextStyle,
    bold: Set<Int>,
): Map<Int, InlineTextContent> {
    val color = MaterialTheme.colorScheme.onSurface
    val config =
        remember(style.fontSize, color) {
            LatexConfig(
                fontSize = style.fontSize * 1.2f,
                theme = LatexTheme.light(color),
                accessibilityEnabled = true,
            )
        }
    val measurer = rememberLatexMeasurer(config)
    val mutex = remember(measurer) { Mutex() }
    val dark = isSystemInDarkTheme()
    var results by
        remember(formulas, measurer, bold, dark) {
            mutableStateOf(emptyMap<Int, InlineTextContent>())
        }
    LaunchedEffect(formulas, measurer, bold, dark) {
        results =
            withContext(Dispatchers.Default) {
                mutex.withLock {
                    buildMap {
                        for ((index, formula) in formulas.withIndex()) {
                            ensureActive()
                            if (!formula.renderable) continue
                            val source =
                                (if (formula.display) "\\displaystyle " else "\\textstyle ") +
                                    if (index in bold) "\\boldsymbol{${formula.source}}"
                                    else formula.source
                            measurer.inlineContent(source, config, dark)?.let { put(index, it) }
                        }
                    }
                }
            }
    }
    return results
}

@Composable
internal fun MathTextRun(
    annotated: AnnotatedString,
    marks: List<MarkdownMark>,
    offset: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    if (marks.isEmpty()) {
        Text(annotated, modifier, style = style, fontWeight = fontWeight, textAlign = textAlign)
        return
    }
    val formulas = remember(marks) { marks.mapNotNull { it.formula } }
    val bold =
        remember(annotated, marks, offset, fontWeight, style.fontWeight) {
            marks.indices.filterTo(mutableSetOf()) { index ->
                (fontWeight ?: style.fontWeight ?: FontWeight.Normal) >= FontWeight.SemiBold ||
                    annotated.spanStyles.any {
                        it.start <= marks[index].start - offset &&
                            it.end >= marks[index].end - offset &&
                            (it.item.fontWeight ?: FontWeight.Normal) >= FontWeight.SemiBold
                    }
            }
        }
    val measured = measuredFormulas(formulas, style, bold)
    val text =
        remember(annotated, marks, offset, measured) {
            buildAnnotatedString {
                var previous = 0
                for ((index, mark) in marks.withIndex()) {
                    val start = mark.start - offset
                    val end = mark.end - offset
                    append(annotated.subSequence(previous, start))
                    if (index in measured) {
                        val targetStart = length
                        appendInlineContent(
                            index.toString(),
                            annotated.text.substring(start, end).replace('\n', ' '),
                        )
                        // Keep surrounding links clickable even when their entire label is a
                        // formula.
                        for (link in annotated.getLinkAnnotations(start, end)) when (
                            val item = link.item
                        ) {
                            is LinkAnnotation.Clickable -> addLink(item, targetStart, length)
                            is LinkAnnotation.Url -> addLink(item, targetStart, length)
                        }
                    } else append(annotated.subSequence(start, end))
                    previous = end
                }
                append(annotated.subSequence(previous, annotated.length))
            }
        }
    Text(
        text,
        modifier,
        style = style,
        fontWeight = fontWeight,
        textAlign = textAlign,
        inlineContent = measured.mapKeys { it.key.toString() },
    )
}

@Composable
internal fun MathDisplay(
    formula: MarkdownFormula,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val measured = measuredFormulas(listOf(formula), style, emptySet())[0]
    Box(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        if (measured == null)
            Text(
                formula.source,
                style =
                    style.copy(
                        fontSize = style.fontSize * 0.875f,
                        fontFamily =
                            dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont.current,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else
            Text(
                buildAnnotatedString {
                    appendInlineContent("formula", formula.source.replace('\n', ' '))
                },
                style = style,
                inlineContent = mapOf("formula" to measured),
            )
    }
}

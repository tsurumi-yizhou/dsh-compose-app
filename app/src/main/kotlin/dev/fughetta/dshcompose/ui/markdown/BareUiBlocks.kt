package dev.fughetta.dshcompose.ui.markdown

import dev.fughetta.dshcompose.ui.dynamicui.DynamicUiParser
import dev.fughetta.dshcompose.ui.dynamicui.UiParse
import org.commonmark.node.Block
import org.commonmark.node.CustomBlock
import org.commonmark.parser.block.*

internal data class BareUiSpan(
    val start: Int,
    val end: Int,
    val source: String,
    val parsed: UiParse,
)

internal class BareUiNode(val span: BareUiSpan) : CustomBlock()

private val bareRoot = Regex("""(?m)^ {0,3}(?=\{)""")

internal fun hasBareUiCandidate(source: String): Boolean = bareRoot.containsMatchIn(source)

/** Inspect independent root objects once; do not promote components nested in unrelated JSON. */
internal fun bareUiSpans(source: String): List<BareUiSpan> {
    val spans = mutableListOf<BareUiSpan>()
    var consumed = 0
    for (match in bareRoot.findAll(source)) {
        val start = match.range.last + 1
        if (start < consumed) continue
        val limit = minOf(source.length, start + DynamicUiParser.MaxBytes)
        val end = jsonObjectEnd(source, start, limit)
        consumed = if (end != null) end else limit
        if (end == null) continue
        var tail = end
        while (tail < source.length && source[tail] != '\n' && source[tail].isWhitespace()) tail++
        if (tail < source.length && source[tail] != '\n') continue
        val text = source.substring(start, end)
        val parsed = DynamicUiParser.parse(text)
        if (parsed.view != null) spans.add(BareUiSpan(start, end, text, parsed))
    }
    return spans
}

private fun jsonObjectEnd(source: String, start: Int, limit: Int): Int? {
    var depth = 0
    var quoted = false
    var escaped = false
    var index = start
    while (index < limit) {
        val char = source[index]
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else
            when {
                char == '"' -> quoted = true
                source.startsWith("//", index) -> {
                    index += 2
                    while (index < limit && source[index] != '\n') index++
                    continue
                }
                source.startsWith("/*", index) -> {
                    index += 2
                    while (index < limit && !source.startsWith("*/", index)) index++
                    index = minOf(index + 2, limit)
                    continue
                }
                char == '{' || char == '[' -> {
                    depth++
                    if (depth > 64) return null
                }
                char == '}' || char == ']' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        index++
    }
    return null
}

internal fun mergeProtectedAreas(areas: List<IntRange>): List<IntRange> = buildList {
    for (area in areas.sortedBy { it.first }) {
        val previous = lastOrNull()
        if (previous != null && area.first <= previous.last + 1)
            this[lastIndex] = previous.first..maxOf(previous.last, area.last)
        else add(area)
    }
}

internal class BareUiBlockFactory(spans: List<BareUiSpan>, private val protected: List<IntRange>) :
    AbstractBlockParserFactory() {
    private val starts = spans.associateBy { it.start }

    override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
        if (state.indent >= 4) return null
        val absolute = (state.line.sourceSpan?.inputIndex ?: return null) + state.nextNonSpaceIndex
        val span = starts[absolute] ?: return null
        if (protectedAt(protected, absolute)) return null
        return BlockStart.of(Body(BareUiNode(span))).atIndex(state.line.content.length)
    }

    private class Body(private val node: BareUiNode) : AbstractBlockParser() {
        override fun getBlock(): Block = node

        override fun tryContinue(state: ParserState): BlockContinue? {
            val start = state.line.sourceSpan?.inputIndex ?: return null
            return if (start >= node.span.end) null
            else BlockContinue.atIndex(state.line.content.length)
        }
    }
}

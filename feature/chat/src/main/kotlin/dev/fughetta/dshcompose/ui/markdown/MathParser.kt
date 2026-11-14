package dev.fughetta.dshcompose.ui.markdown

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.ParseDiagnostic
import org.commonmark.node.*
import org.commonmark.parser.*
import org.commonmark.parser.beta.*
import org.commonmark.parser.block.*

internal data class MarkdownFormula(
    val source: String,
    val display: Boolean,
    val complete: Boolean,
    val renderable: Boolean,
)

internal class MathInlineNode(val source: String, val display: Boolean, val complete: Boolean) :
    CustomNode()

internal class MathBlockNode(val opener: String) : CustomBlock() {
    val source = StringBuilder()
    var complete = false
}

/** Formula bodies are opaque to Markdown, including underscores, brackets and HTML-looking TeX. */
internal class MathInlineFactory : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = setOf('$', '\\')

    override fun create(): InlineContentParser =
        object : InlineContentParser {
            override fun tryParse(state: InlineParserState): ParsedInline? {
                val scanner = state.scanner()
                val start = scanner.position()
                val previous = scanner.peekPreviousCodePoint()
                val dollar = scanner.peek() == '$'
                scanner.next()
                val display: Boolean
                val closer: String
                if (dollar) {
                    if (previous == '$'.code) {
                        scanner.setPosition(start)
                        return null
                    }
                    display = scanner.next('$')
                    if (
                        !display && (scanner.peek() == Scanner.END || scanner.peek().isWhitespace())
                    ) {
                        scanner.setPosition(start)
                        return null
                    }
                    closer = if (display) "$$" else "$"
                } else {
                    display = scanner.peek() == '['
                    if (!display && scanner.peek() != '(') {
                        scanner.setPosition(start)
                        return null
                    }
                    scanner.next()
                    closer = if (display) "\\]" else "\\)"
                }
                val body = scanner.position()
                while (scanner.hasNext()) {
                    val end = scanner.position()
                    val before = scanner.peekPreviousCodePoint()
                    if (scanner.next(closer)) {
                        val source = scanner.getSource(body, end).content
                        val invalidPrice =
                            dollar &&
                                !display &&
                                (before.toChar().isWhitespace() ||
                                    scanner.peek() in '0'..'9' ||
                                    scanner.peek() == '$' ||
                                    readsAsPrice(source))
                        if (source.isBlank() || invalidPrice) {
                            scanner.setPosition(start)
                            return null
                        }
                        return ParsedInline.of(
                            MathInlineNode(source.trim(), display, true),
                            scanner.position(),
                        )
                    }
                    val char = scanner.peek()
                    if (dollar && !display && (char == '\n' || char == '`')) {
                        scanner.setPosition(start)
                        return null
                    }
                    scanner.next()
                    if (char == '\\' && scanner.hasNext()) scanner.next()
                }
                val source = scanner.getSource(body, scanner.position()).content
                if (display && source.isNotBlank())
                    return ParsedInline.of(
                        MathInlineNode(source.trim(), true, false),
                        scanner.position(),
                    )
                scanner.setPosition(start)
                return null
            }
        }
}

private val formulaOperators = Regex("""[\\^_{}=+<>]""")

private fun readsAsPrice(source: String) =
    source.firstOrNull() in '0'..'9' &&
        source.any { it.isWhitespace() } &&
        !formulaOperators.containsMatchIn(source)

private fun mathCloser(line: String, closer: String, from: Int = 0): Int {
    var index = from
    while (index < line.length) {
        if (line.startsWith(closer, index)) return index
        index += if (line[index] == '\\' && index + 1 < line.length) 2 else 1
    }
    return -1
}

/**
 * Put a multiline display opener on its own container line before CommonMark's letter fast path.
 */
internal fun normalizeMathBlockStarts(
    source: String,
    protectedAreas: () -> List<IntRange>,
): String {
    if (!source.contains("$$") && !source.contains("\\[")) return source
    data class Insertion(val at: Int, val prefix: String)
    val insertions = mutableListOf<Insertion>()
    var lineStart = 0
    var bodyUntil = 0
    var shielded: List<IntRange>? = null
    while (lineStart < source.length) {
        val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
        val line = source.substring(lineStart, lineEnd)
        val prefix = containerPrefix.find(line)?.value.orEmpty()
        var at = prefix.length
        if (line.substring(at).startsWith('#')) {
            lineStart = lineEnd + 1
            continue
        }
        while (at < line.length) {
            val absolute = lineStart + at
            if (
                absolute >= bodyUntil && (line.startsWith("$$", at) || line.startsWith("\\[", at))
            ) {
                val areas = shielded ?: protectedAreas().also { shielded = it }
                if (!protectedAt(areas, absolute)) {
                    val closer = if (line.startsWith("$$", at)) "$$" else "\\]"
                    val end = mathCloser(line.substring(at + 2), closer)
                    if (end >= 0) {
                        at += end + 4
                        continue
                    }
                    if (at > prefix.length)
                        insertions.add(
                            Insertion(
                                absolute,
                                prefix.replace(listPrefixMarker) { " ".repeat(it.value.length) },
                            ),
                        )
                    // A subsequent equation inside this body is TeX, not another Markdown opener.
                    val close = mathCloser(source, closer, absolute + 2)
                    val fence = nextFence.find(source, absolute + 2)?.range?.first
                    bodyUntil =
                        minOf(if (close < 0) source.length else close + 2, fence ?: source.length)
                    break
                }
            }
            at += if (line[at] == '\\' && at + 1 < line.length) 2 else 1
        }
        lineStart = lineEnd + 1
    }
    if (insertions.isEmpty()) return source
    return buildString {
        var previous = 0
        for (insertion in insertions) {
            append(source, previous, insertion.at)
            append('\n')
            append(insertion.prefix)
            previous = insertion.at
        }
        append(source, previous, source.length)
    }
}

private val containerPrefix =
    Regex("""^(?:(?:[ \t]*>[ \t]*)|(?:[ \t]*(?:[-+*]|\d+[.)])[ \t]+)|[ \t]+)*""")
private val listPrefixMarker = Regex("""[-+*]|\d+[.)]""")
private val nextFence = Regex("""(?m)^ {0,3}(?:`{3,}|~{3,})""")

internal fun mathProtectedAreas(root: Node): List<IntRange> = buildList {
    fun visit(node: Node) {
        if (
            node is Code ||
                node is RawHtmlInlineNode ||
                node is FencedCodeBlock ||
                node is IndentedCodeBlock ||
                node is HtmlBlock ||
                node is Link ||
                node is Image ||
                node is MathInlineNode ||
                node is MathBlockNode ||
                node is BareUiNode
        ) {
            if (node.sourceSpans.isNotEmpty())
                add(
                    node.sourceSpans.minOf { it.inputIndex } until
                        node.sourceSpans.maxOf { it.inputIndex + it.length },
                )
            return
        }
        var child = node.firstChild
        while (child != null) {
            visit(child)
            child = child.next
        }
    }
    visit(root)
}
    .sortedBy { it.first }

internal class MathBlockFactory(private val protected: List<IntRange>) :
    AbstractBlockParserFactory() {
    override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
        if (state.indent >= 4) return null
        val line = state.line.content.toString().substring(state.nextNonSpaceIndex)
        val inputIndex = (state.line.sourceSpan?.inputIndex ?: 0) + state.nextNonSpaceIndex
        if (protectedAt(protected, inputIndex)) return null
        val opener =
            when {
                line.startsWith("$$") -> "$$"
                line.startsWith("\\[") -> "\\["
                else -> return null
            }
        val closer = if (opener == "$$") "$$" else "\\]"
        val body = line.substring(2)
        val end = mathCloser(body, closer)
        // Mixed prose on the same line belongs to the inline parser.
        if (end >= 0 && body.substring(end + 2).isNotBlank()) return null
        val node = MathBlockNode(opener)
        node.source.append(if (end >= 0) body.substring(0, end) else body)
        node.complete = end >= 0
        return BlockStart.of(FormulaBlock(node, closer)).atIndex(state.line.content.length)
    }

    private class FormulaBlock(private val node: MathBlockNode, private val closer: String) :
        AbstractBlockParser() {
        private var first = true
        private var tail: SourceLine? = null
        private var paragraph: Paragraph? = null

        override fun getBlock(): Block = node

        override fun tryContinue(state: ParserState): BlockContinue? {
            if (node.complete) return null
            val line = state.line.substring(state.index, state.line.content.length)
            if (state.indent < 4 && fence.containsMatchIn(line.content)) return null
            val end = mathCloser(line.content.toString(), closer)
            if (end < 0) return BlockContinue.atIndex(state.index)
            node.source.append('\n').append(line.content.subSequence(0, end))
            node.complete = true
            line.sourceSpan?.let(node::addSourceSpan)
            tail = line.substring(end + 2, line.content.length).takeIf { it.content.isNotBlank() }
            return BlockContinue.finished()
        }

        override fun addLine(line: SourceLine) {
            if (first) first = false else node.source.append('\n').append(line.content)
        }

        override fun closeBlock() {
            if (tail != null) paragraph = Paragraph().also { node.insertAfter(it) }
        }

        override fun parseInlines(inlineParser: InlineParser) {
            val remaining = tail ?: return
            inlineParser.parse(SourceLines.of(remaining), paragraph ?: return)
        }

        companion object {
            private val fence = Regex("""^ {0,3}(?:`{3,}|~{3,})""")
        }
    }
}

internal fun protectedAt(areas: List<IntRange>, index: Int): Boolean {
    var low = 0
    var high = areas.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (areas[mid].last < index) low = mid + 1 else high = mid
    }
    return areas.getOrNull(low)?.let { index in it } == true
}

/**
 * Bound work before the recursive LaTeX parser; oversized or invalid formulas retain their source.
 */
internal fun markdownFormula(source: String, display: Boolean, complete: Boolean): MarkdownFormula {
    val text = source.trim()
    var depth = 0
    var commands = 0
    var escaped = false
    var bounded = text.length <= 8192
    if (bounded)
        for (char in text) {
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                commands++
                if (commands > 128) {
                    bounded = false
                    break
                }
            } else if (char == '{') {
                depth++
                if (depth > 64) {
                    bounded = false
                    break
                }
            } else if (char == '}') depth = (depth - 1).coerceAtLeast(0)
        }
    val valid =
        complete &&
            bounded &&
            text.isNotBlank() &&
            try {
                LatexParser().parseWithDiagnostics(text).diagnostics.none {
                    it.severity == ParseDiagnostic.Severity.ERROR || it.category in structuralErrors
                }
            } catch (_: IllegalArgumentException) {
                false
            }
    return MarkdownFormula(text, display, complete, valid)
}

private val structuralErrors =
    setOf(
        ParseDiagnostic.Category.MISSING_BRACE,
        ParseDiagnostic.Category.MISSING_BRACKET,
        ParseDiagnostic.Category.MISMATCHED_ENVIRONMENT,
        ParseDiagnostic.Category.INVALID_ARGUMENT,
        ParseDiagnostic.Category.MACRO_ERROR,
        ParseDiagnostic.Category.UNKNOWN_COMMAND,
    )

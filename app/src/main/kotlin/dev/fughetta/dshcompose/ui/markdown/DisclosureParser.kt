package dev.fughetta.dshcompose.ui.markdown

import java.util.UUID
import org.commonmark.node.*
import org.commonmark.parser.*
import org.commonmark.parser.block.*

internal class DetailsNode(val position: Int, val initiallyOpen: Boolean) : CustomBlock()

internal class SummaryNode : CustomBlock()

internal class DisclosureEndNode : CustomBlock()

/** HTML containers use the same CommonMark parse as their surrounding text and link definitions. */
internal class DisclosureParser(
    private val inlineParser: Parser,
    private val builder: (String) -> Parser.Builder,
    private val normalize: (String) -> String,
    private val extraProtected: (String) -> List<IntRange>,
) {
    private fun parseMarkdown(
        source: String,
        positioned: Boolean = false,
        factory: BlockParserFactory? = null,
    ): Node {
        val input = if (positioned) source else normalize(source)
        if (
            !positioned &&
                factory == null &&
                !input.contains("$$") &&
                !input.contains("\\[") &&
                !hasBareUiCandidate(input)
        )
            return inlineParser.parse(input)
        val parser = builder(input)
        if (positioned) parser.includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        if (factory != null) parser.customBlockParserFactory(factory)
        return parser.build().parse(input)
    }

    private data class Area(val start: Int, val end: Int)

    private data class Tag(
        val start: Int,
        val end: Int,
        val name: String,
        val closing: Boolean,
        val attributes: String,
    )

    private data class Segment(
        val start: Int,
        val end: Int,
        val originalStart: Int,
        val originalEnd: Int,
        val replaced: Boolean,
    )

    private class Rewritten(val text: String, val segments: List<Segment>) {
        fun original(index: Int, end: Boolean): Int {
            val point = if (end && index > 0) index - 1 else index
            var low = 0
            var high = segments.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (segments[middle].end <= point) low = middle + 1 else high = middle
            }
            val segment = segments.getOrNull(low) ?: return segments.lastOrNull()?.originalEnd ?: 0
            return if (segment.replaced) {
                if (end) segment.originalEnd else segment.originalStart
            } else segment.originalStart + index - segment.start
        }
    }

    private val tags =
        Regex("""(?is)<(/?)(details|summary)(?=[\s/>])((?:[^'"<>]|'[^']*'|"[^"]*")*)>""")
    private val attributes = Regex("""([^\s=/>]+)(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?""")
    private val prefix =
        Regex(
            """^(?:(?:[ \t]*>[ \t]*)|(?:[ \t]*(?:[-+*]|\d+[.)])[ \t]+)|[ \t]+)*""",
            RegexOption.MULTILINE,
        )
    private val containerHtml = Regex("""(?is)^\s*</?(?:details|summary)(?=[\s/>])""")
    private val listMarker = Regex("""[-+*]|\d+[.)]""")

    fun parse(input: String): Node {
        if (!input.contains("<details", ignoreCase = true)) return parseMarkdown(input)
        val source = normalize(input)
        val candidates =
            tags
                .findAll(source)
                .map {
                    Tag(
                        it.range.first,
                        it.range.last + 1,
                        it.groupValues[2].lowercase(),
                        it.groupValues[1].isNotEmpty(),
                        it.groupValues[3],
                    )
                }
                .toList()
        if (candidates.isEmpty()) return parseMarkdown(source)
        val original = parseMarkdown(source, true)
        val shielded =
            protectedAreas(original) + extraProtected(source).map { Area(it.first, it.last + 1) }
        val possible = outside(candidates, shielded)
        if (possible.none { it.name == "details" && !it.closing }) return parseMarkdown(source)
        // Removing only the container tags lets CommonMark find code inside HTML blocks too.
        val proxy = rewrite(source, possible) { "\u2060" }
        val innerProtected =
            protectedAreas(parseMarkdown(proxy.text, true)).map {
                Area(proxy.original(it.start, false), proxy.original(it.end, true))
            }
        val valid = outside(possible, shielded + innerProtected)
        val marker = "\uE000${UUID.randomUUID()}\uE001"
        data class Frame(val name: String, var summary: Boolean = false)
        val stack = mutableListOf<Frame>()
        val operations = mutableMapOf<Int, String>()
        for (tag in valid) when {
            tag.name == "details" && !tag.closing -> {
                stack.add(Frame("details"))
                val open =
                    attributes.findAll(tag.attributes).any {
                        it.groupValues[1].equals("open", true)
                    }
                operations[tag.start] = "D:${tag.start}:${if (open) 1 else 0}"
            }
            tag.name == "summary" &&
                !tag.closing &&
                stack.lastOrNull()?.let { it.name == "details" && !it.summary } == true -> {
                stack.last().summary = true
                stack.add(Frame("summary"))
                operations[tag.start] = "S"
            }
            tag.name == "summary" && tag.closing && stack.lastOrNull()?.name == "summary" -> {
                stack.removeAt(stack.lastIndex)
                operations[tag.start] = "s"
            }
            tag.name == "details" && tag.closing && stack.any { it.name == "details" } -> {
                while (stack.removeAt(stack.lastIndex).name != "details") {}
                operations[tag.start] = "d"
            }
        }
        if (operations.isEmpty()) return parseMarkdown(source)
        val normalized =
            rewrite(source, valid.filter { it.start in operations }) {
                marker + operations.getValue(it.start)
            }
        return parseMarkdown(normalized.text, factory = Factory(marker))
    }

    private fun rewrite(source: String, tags: List<Tag>, value: (Tag) -> String): Rewritten {
        val output = StringBuilder()
        val segments = mutableListOf<Segment>()
        var previous = 0
        var lineStart = 0
        var lineEnd = source.indexOf('\n').let { if (it < 0) source.length else it }
        var initial = prefix.find(source, lineStart)?.value.orEmpty()
        var continuation = initial.replace(listMarker) { " ".repeat(it.value.length) }
        for (tag in tags) {
            if (tag.start > previous) {
                val start = output.length
                output.append(source, previous, tag.start)
                segments.add(Segment(start, output.length, previous, tag.start, false))
            }
            while (tag.start > lineEnd) {
                lineStart = lineEnd + 1
                lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
                initial = prefix.find(source, lineStart)?.value.orEmpty()
                continuation = initial.replace(listMarker) { " ".repeat(it.value.length) }
            }
            val start = output.length
            if (tag.start != lineStart + initial.length) output.append('\n').append(continuation)
            output.append(value(tag)).append('\n').append(continuation)
            segments.add(Segment(start, output.length, tag.start, tag.end, true))
            previous = tag.end
        }
        if (previous < source.length) {
            val start = output.length
            output.append(source, previous, source.length)
            segments.add(Segment(start, output.length, previous, source.length, false))
        }
        return Rewritten(output.toString(), segments)
    }

    private fun protectedAreas(root: Node): List<Area> = buildList {
        fun visit(node: Node) {
            val rawHtml = node is HtmlBlock && !containerHtml.containsMatchIn(node.literal)
            if (
                node is Code ||
                    node is RawHtmlInlineNode ||
                    node is FencedCodeBlock ||
                    node is IndentedCodeBlock ||
                    node is MathInlineNode ||
                    node is MathBlockNode ||
                    node is BareUiNode ||
                    rawHtml
            ) {
                if (node.sourceSpans.isNotEmpty())
                    add(
                        Area(
                            node.sourceSpans.minOf { it.inputIndex },
                            node.sourceSpans.maxOf { it.inputIndex + it.length },
                        ),
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

    private fun outside(tags: List<Tag>, areas: List<Area>): List<Tag> {
        val merged = mutableListOf<Area>()
        for (area in areas.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && area.start <= last.end)
                merged[merged.lastIndex] = Area(last.start, maxOf(last.end, area.end))
            else merged.add(area)
        }
        var index = 0
        return tags.filter { tag ->
            while (index < merged.size && merged[index].end <= tag.start) index++
            index >= merged.size || merged[index].start >= tag.end
        }
    }

    private class Factory(private val marker: String) : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matched: MatchedBlockParser): BlockStart? {
            val line = state.line.content.toString().substring(state.nextNonSpaceIndex)
            if (!line.startsWith(marker)) return BlockStart.none()
            val operation = line.removePrefix(marker)
            val block =
                when {
                    operation.startsWith("D:") ->
                        operation.split(':').let { DetailsNode(it[1].toInt(), it[2] == "1") }
                    operation == "S" -> SummaryNode()
                    else -> DisclosureEndNode()
                }
            return BlockStart.of(Container(block, marker, if (block is DetailsNode) "d" else "s"))
                .atIndex(state.line.content.length)
        }
    }

    private class Container(
        private val node: Block,
        private val marker: String,
        private val end: String,
    ) : AbstractBlockParser() {
        override fun getBlock(): Block = node

        override fun isContainer(): Boolean = node !is DisclosureEndNode

        override fun canContain(child: Block): Boolean = true

        override fun tryContinue(state: ParserState): BlockContinue? {
            if (node is DisclosureEndNode) return BlockContinue.none()
            val line = state.line.content.toString().substring(state.nextNonSpaceIndex).trimEnd()
            if (line == marker + end) {
                var active: Node? = state.activeBlockParser.block
                while (active != null && active !== node) {
                    if (
                        node is DetailsNode && active is DetailsNode ||
                            node is SummaryNode && active is SummaryNode
                    )
                        return BlockContinue.atIndex(state.index)
                    active = active.parent
                }
                return BlockContinue.finished()
            }
            return BlockContinue.atIndex(state.index)
        }
    }
}

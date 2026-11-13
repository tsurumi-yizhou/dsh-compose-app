package dev.fughetta.dshcompose.ui.markdown

import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.composer.parseMessageReferences
import dev.fughetta.dshcompose.ui.composer.referenceDisplay
import dev.fughetta.dshcompose.ui.dynamicui.DynamicUiParser
import dev.fughetta.dshcompose.ui.dynamicui.UiParse
import kotlinx.collections.immutable.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

internal enum class MarkdownMarkKind {
    Strong,
    Emphasis,
    Strike,
    Code,
    Link,
    Image,
    Reference,
    Underline,
    Highlight,
    Superscript,
    Subscript,
    Math,
}

internal data class MarkdownMark(
    val start: Int,
    val end: Int,
    val kind: MarkdownMarkKind,
    val target: String? = null,
    val imageIndex: Int? = null,
    val formula: MarkdownFormula? = null,
    val imageSize: HtmlImageSize? = null,
)

internal data class MarkdownText(val text: String, val marks: PersistentList<MarkdownMark>)

internal enum class TableAlignment {
    Left,
    Center,
    Right,
}

internal data class MarkdownCell(
    val content: MarkdownText,
    val header: Boolean,
    val alignment: TableAlignment,
)

internal sealed interface MarkdownBlock {
    data class Text(val content: MarkdownText, val heading: Int = 0) : MarkdownBlock

    data class Code(val text: String, val language: String) : MarkdownBlock

    data class Visual(val source: String, val closed: Boolean, val parsed: UiParse?) : MarkdownBlock

    data class Math(val formula: MarkdownFormula) : MarkdownBlock

    data class Disclosure(
        val position: Int,
        val initiallyOpen: Boolean,
        val title: MarkdownText?,
        val blocks: PersistentList<MarkdownBlock>,
    ) : MarkdownBlock

    data class Quote(val blocks: PersistentList<MarkdownBlock>) : MarkdownBlock

    data class ListEntry(val marker: String, val blocks: PersistentList<MarkdownBlock>) :
        MarkdownBlock

    data class Table(val rows: PersistentList<PersistentList<MarkdownCell>>) : MarkdownBlock

    data object Rule : MarkdownBlock
}

internal data class MarkdownGalleryImage(val address: String, val alt: String) {
    override fun toString() = "MarkdownGalleryImage(alt=$alt)"
}

internal data class MarkdownDocument(
    val blocks: PersistentList<MarkdownBlock>,
    val images: PersistentList<MarkdownGalleryImage>,
)

/** Immutable output; the CommonMark AST never escapes the parsing worker. */
internal object MarkdownDocuments {
    private fun baseBuilder() =
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                ),
            )
            .customInlineContentParserFactory(RawHtmlInlineFactory())
            .maxOpenBlockParsers(128)
            .maxInlineNesting(128)

    private val literalParser =
        baseBuilder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    private val inlineParser =
        baseBuilder().customInlineContentParserFactory(MathInlineFactory()).build()

    private fun builder(
        source: String,
        protectedAreas: (String) -> List<IntRange>,
    ): Parser.Builder {
        val display = source.contains("$$") || source.contains("\\[")
        val result = baseBuilder().customInlineContentParserFactory(MathInlineFactory())
        if (display)
            result
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .customBlockParserFactory(MathBlockFactory(protectedAreas(source)))
        return result
    }

    private val inlineHtml =
        Regex(
            """(?is)^<(/?)(b|strong|i|em|s|del|strike|code|kbd|samp|u|ins|mark|sup|sub|a)(?=[\s/>])(?:[^'">]|'[^']*'|"[^"]*")*>$""",
        )
    private val htmlBreak = Regex("""(?is)^<br(?=[\s/>])(?:[^'">]|'[^']*'|"[^"]*")*>$""")

    fun parse(
        source: String,
        labels: ReferenceLabelIndex,
        allowBareViews: Boolean = true,
    ): MarkdownDocument {
        // Share code ranges between formula normalization and parsing; retain no message cache.
        val protected = mutableMapOf<String, List<IntRange>>()
        val cards = mutableMapOf<String, List<BareUiSpan>>()
        fun areas(input: String) =
            protected.getOrPut(input) { mathProtectedAreas(literalParser.parse(input)) }
        fun bare(input: String) =
            if (allowBareViews) cards.getOrPut(input) { bareUiSpans(input) } else emptyList()
        val disclosures =
            DisclosureParser(
                inlineParser,
                { input ->
                    val spans = bare(input)
                    val result = builder(input, ::areas)
                    if (spans.isNotEmpty()) {
                        val shielded =
                            mathProtectedAreas(
                                builder(input, ::areas)
                                    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                                    .build()
                                    .parse(input),
                            )
                        result
                            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                            .customBlockParserFactory(BareUiBlockFactory(spans, shielded))
                    }
                    result
                },
                normalize = { input ->
                    normalizeMathBlockStarts(input) {
                        mergeProtectedAreas(
                            areas(input) + bare(input).map { it.start until it.end },
                        )
                    }
                },
                extraProtected = { input -> bare(input).map { it.start until it.end } },
            )
        val images = mutableListOf<MarkdownGalleryImage>()
        return MarkdownDocument(
            blocks(disclosures.parse(source), labels, images),
            images.toPersistentList(),
        )
    }

    private fun blocks(
        parent: Node,
        labels: ReferenceLabelIndex,
        images: MutableList<MarkdownGalleryImage>,
        omit: Node? = null,
    ): PersistentList<MarkdownBlock> = buildList {
        for (node in children(parent).filter { it !== omit }) when (node) {
            is DetailsNode -> {
                val summary = children(node).filterIsInstance<SummaryNode>().firstOrNull()
                val title = summary?.let { inlines(it, labels, images) }
                add(
                    MarkdownBlock.Disclosure(
                        node.position,
                        node.initiallyOpen,
                        title,
                        blocks(node, labels, images, summary),
                    ),
                )
            }
            is DisclosureEndNode -> Unit
            is BareUiNode -> add(MarkdownBlock.Visual(node.span.source, true, node.span.parsed))
            is MathBlockNode -> {
                val source = node.source.toString()
                if (source.isBlank())
                    add(
                        MarkdownBlock.Text(
                            MarkdownText(
                                node.opener +
                                    source +
                                    if (node.complete) {
                                        if (node.opener == "$$") "$$" else "\\]"
                                    } else "",
                                persistentListOf(),
                            ),
                        ),
                    )
                else add(MarkdownBlock.Math(markdownFormula(source, true, node.complete)))
            }
            is Heading -> add(MarkdownBlock.Text(inlines(node, labels, images), node.level))
            is Paragraph -> add(MarkdownBlock.Text(inlines(node, labels, images)))
            is FencedCodeBlock -> {
                val language = node.info.trim().substringBefore(' ')
                if (language == "dsh-ui" && node.openingFenceLength != null) {
                    val closed = node.closingFenceLength != null
                    add(
                        MarkdownBlock.Visual(
                            node.literal,
                            closed,
                            if (closed) DynamicUiParser.parse(node.literal) else null,
                        ),
                    )
                } else add(MarkdownBlock.Code(node.literal, language))
            }
            is IndentedCodeBlock -> add(MarkdownBlock.Code(node.literal, ""))
            is BlockQuote -> add(MarkdownBlock.Quote(blocks(node, labels, images)))
            is BulletList ->
                children(node).forEach { item -> add(listEntry(item, "•", labels, images)) }
            is OrderedList ->
                children(node).forEachIndexed { index, item ->
                    add(
                        listEntry(
                            item,
                            "${(node.markerStartNumber ?: 1) + index}${node.markerDelimiter ?: "."}",
                            labels,
                            images,
                        ),
                    )
                }
            is ThematicBreak -> add(MarkdownBlock.Rule)
            is HtmlBlock ->
                addAll(
                    blocks(
                        HtmlMarkdown(node.literal, node.sourceSpans.firstOrNull()?.inputIndex ?: 0)
                            .parse(),
                        labels,
                        images,
                    ),
                )
            is TableBlock ->
                add(
                    MarkdownBlock.Table(
                        buildList {
                            for (section in children(node)) for (row in children(section)) add(
                                children(row)
                                    .filterIsInstance<TableCell>()
                                    .map { cell ->
                                        MarkdownCell(
                                            inlines(cell, labels, images),
                                            cell.isHeader,
                                            when (cell.alignment) {
                                                TableCell.Alignment.CENTER -> TableAlignment.Center
                                                TableCell.Alignment.RIGHT -> TableAlignment.Right
                                                else -> TableAlignment.Left
                                            },
                                        )
                                    }
                                    .toPersistentList(),
                            )
                        }
                            .toPersistentList(),
                    ),
                )
            is LinkReferenceDefinition -> Unit
            else -> addAll(blocks(node, labels, images))
        }
    }
        .toPersistentList()

    private fun listEntry(
        item: Node,
        marker: String,
        labels: ReferenceLabelIndex,
        images: MutableList<MarkdownGalleryImage>,
    ): MarkdownBlock.ListEntry {
        val task = children(item).filterIsInstance<TaskListItemMarker>().firstOrNull()
        return MarkdownBlock.ListEntry(
            if (task == null) marker else if (task.isChecked) "☑" else "☐",
            blocks(item, labels, images),
        )
    }

    private fun inlines(
        parent: Node,
        labels: ReferenceLabelIndex,
        images: MutableList<MarkdownGalleryImage>,
    ): MarkdownText {
        val text = StringBuilder()
        val marks = mutableListOf<MarkdownMark>()
        data class OpenHtml(
            val name: String,
            val start: Int,
            val kind: MarkdownMarkKind,
            val target: String?,
        )
        val html = mutableListOf<OpenHtml>()
        fun htmlLiteral(literal: String) {
            if (htmlBreak.matches(literal)) {
                text.append('\n')
                return
            }
            val tag = inlineHtml.matchEntire(literal)
            if (tag == null) {
                text.append(literal)
                return
            }
            val name = tag.groupValues[2].lowercase()
            if (tag.groupValues[1].isNotEmpty()) {
                val index = html.indexOfLast { it.name == name }
                if (index < 0) {
                    text.append(literal)
                    return
                }
                val opened = html.removeAt(index)
                if (text.length > opened.start)
                    marks.add(
                        MarkdownMark(
                            opened.start,
                            text.length,
                            opened.kind,
                            target = opened.target,
                        ),
                    )
            } else
                html.add(
                    OpenHtml(
                        name,
                        text.length,
                        when (name) {
                            "b",
                            "strong" -> MarkdownMarkKind.Strong
                            "i",
                            "em" -> MarkdownMarkKind.Emphasis
                            "s",
                            "del",
                            "strike" -> MarkdownMarkKind.Strike
                            "code",
                            "kbd",
                            "samp" -> MarkdownMarkKind.Code
                            "u",
                            "ins" -> MarkdownMarkKind.Underline
                            "mark" -> MarkdownMarkKind.Highlight
                            "sup" -> MarkdownMarkKind.Superscript
                            "a" -> MarkdownMarkKind.Link
                            else -> MarkdownMarkKind.Subscript
                        },
                        if (name == "a") htmlLinkTarget(literal) else null,
                    ),
                )
        }
        fun append(node: Node) {
            val start = text.length
            val kind =
                when (node) {
                    is StrongEmphasis -> MarkdownMarkKind.Strong
                    is Emphasis -> MarkdownMarkKind.Emphasis
                    is Strikethrough -> MarkdownMarkKind.Strike
                    is Code -> MarkdownMarkKind.Code
                    is Link -> MarkdownMarkKind.Link
                    is Image -> MarkdownMarkKind.Image
                    is MathInlineNode -> MarkdownMarkKind.Math
                    is RawHtmlInlineNode -> MarkdownMarkKind.Code
                    else -> null
                }
            when (node) {
                is Text -> text.append(node.literal)
                is Code -> text.append(node.literal)
                is MathInlineNode -> text.append(node.source)
                is SoftLineBreak -> text.append(' ')
                is HardLineBreak -> text.append('\n')
                is RawHtmlInlineNode ->
                    text.append(
                        if (node.code) org.jsoup.parser.Parser.unescapeEntities(node.body, false)
                        else node.literal,
                    )
                is HtmlInline -> htmlImage(node.literal)?.let(::append) ?: htmlLiteral(node.literal)
                is TaskListItemMarker -> Unit
                is Image -> {
                    children(node).forEach(::append)
                    if (text.length == start) text.append('\uFFFC')
                }
                else -> children(node).forEach(::append)
            }
            if (
                node is Link &&
                    node.destination.startsWith("dsh-session:") &&
                    start > 0 &&
                    text[start - 1] == '@'
            ) {
                marks.add(MarkdownMark(start - 1, text.length, MarkdownMarkKind.Reference))
            } else if (kind != null && text.length > start) {
                marks.add(
                    MarkdownMark(
                        start,
                        text.length,
                        kind,
                        when (node) {
                            is Link -> node.destination
                            is Image -> node.destination
                            else -> null
                        },
                        imageIndex =
                            if (node is Image)
                                images.size.also {
                                    images.add(
                                        MarkdownGalleryImage(
                                            node.destination,
                                            text
                                                .substring(start)
                                                .takeUnless { it == "\uFFFC" }
                                                .orEmpty(),
                                        ),
                                    )
                                }
                            else null,
                        formula =
                            if (node is MathInlineNode)
                                markdownFormula(node.source, node.display, node.complete)
                            else null,
                        imageSize = (node as? HtmlImageNode)?.sizeHint,
                    ),
                )
            }
        }
        for (child in children(parent)) {
            if (parent is SummaryNode && text.isNotEmpty()) text.append(' ')
            append(child)
        }
        for (opened in html) if (text.length > opened.start)
            marks.add(MarkdownMark(opened.start, text.length, opened.kind, target = opened.target))
        val raw = text.toString()
        // Code and link contents are literal. References there must not lose their paths.
        val protected =
            marks
                .filter {
                    it.kind in
                        setOf(
                            MarkdownMarkKind.Code,
                            MarkdownMarkKind.Link,
                            MarkdownMarkKind.Image,
                            MarkdownMarkKind.Reference,
                            MarkdownMarkKind.Math,
                        )
                }
                .sortedBy { it.start }
        var protectedIndex = 0
        val references =
            parseMessageReferences(raw, labels).filter { ref ->
                while (
                    protectedIndex < protected.size && protected[protectedIndex].end <= ref.start
                ) protectedIndex++
                protectedIndex >= protected.size || protected[protectedIndex].start >= ref.end
            }
        val display = referenceDisplay(raw, references)
        val mapped = marks.map {
            it.copy(start = display.originalOffset(it.start), end = display.originalOffset(it.end))
        }
        return MarkdownText(
            display.text,
            (mapped +
                    display.spans.map {
                        MarkdownMark(it.start, it.end, MarkdownMarkKind.Reference)
                    })
                .toPersistentList(),
        )
    }

    private fun children(node: Node): Sequence<Node> = sequence {
        var child = node.firstChild
        while (child != null) {
            yield(child)
            child = child.next
        }
    }
}

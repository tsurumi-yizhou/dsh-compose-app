package dev.fughetta.dshcompose.ui.markdown

import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.beta.*
import org.jsoup.nodes.Attributes
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node as HtmlNode
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser as HtmlParser

internal class HtmlImageNode(attributes: Attributes) :
    Image(attributes["src"], attributes["title"]) {
    val sizeHint = HtmlImageSize(dimension(attributes["width"]), dimension(attributes["height"]))

    init {
        appendChild(Text(attributes["alt"]))
    }
}

internal data class HtmlImageSize(val width: Float?, val height: Float?)

private fun dimension(value: String): Float? =
    value.trim().toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }

private val imageTag = Regex("""(?is)^<img(?=[\s/>])(.*)>$""")

internal fun htmlImage(literal: String): HtmlImageNode? {
    val attributes = imageTag.matchEntire(literal)?.groupValues?.get(1) ?: return null
    val parsed = HtmlParser.htmlParser().parseAttributes(attributes)
    return if (parsed["src"].isBlank()) null else HtmlImageNode(parsed)
}

internal fun htmlLinkTarget(literal: String): String =
    HtmlParser.htmlParser().parseAttributes(literal.substring(2, literal.length - 1))["href"]

internal class RawHtmlInlineNode(val name: String, val body: String, source: String) :
    HtmlInline() {
    init {
        literal = source
    }

    val code
        get() = name in codeTags
}

private val codeTags = setOf("code", "kbd", "samp")
private val rawTags =
    codeTags +
        setOf("script", "style", "textarea", "iframe", "object", "template", "svg", "math", "pre")

/** Consume raw HTML as one node so markup inside code/scripts cannot create image requests. */
internal class RawHtmlInlineFactory : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = setOf('<')

    override fun create(): InlineContentParser =
        object : InlineContentParser {
            override fun tryParse(state: InlineParserState): ParsedInline? {
                val scanner = state.scanner()
                val start = scanner.position()
                scanner.next()
                val name = buildString {
                    while (scanner.peek() in 'a'..'z' || scanner.peek() in 'A'..'Z') {
                        append(scanner.peek().lowercaseChar())
                        scanner.next()
                    }
                }
                if (
                    name !in rawTags ||
                        !(scanner.peek().isWhitespace() ||
                            scanner.peek() == '>' ||
                            scanner.peek() == '/')
                ) {
                    scanner.setPosition(start)
                    return null
                }
                var quote: Char? = null
                var opened = false
                while (scanner.hasNext()) {
                    val char = scanner.peek()
                    scanner.next()
                    if (quote != null) {
                        if (quote == char) quote = null
                    } else if (char == '\'' || char == '"') quote = char
                    else if (char == '>') {
                        opened = true
                        break
                    }
                }
                if (!opened) {
                    scanner.setPosition(start)
                    return null
                }
                val body = scanner.position()
                while (scanner.hasNext()) {
                    val end = scanner.position()
                    if (scanner.next('<') && scanner.next('/')) {
                        var matches = true
                        for (char in name) {
                            if (scanner.peek().lowercaseChar() != char) {
                                matches = false
                                break
                            }
                            scanner.next()
                        }
                        if (matches) {
                            while (scanner.peek().isWhitespace()) scanner.next()
                            if (scanner.next('>'))
                                return ParsedInline.of(
                                    RawHtmlInlineNode(
                                        name,
                                        scanner.getSource(body, end).content,
                                        scanner.getSource(start, scanner.position()).content,
                                    ),
                                    scanner.position(),
                                )
                        }
                    }
                    scanner.setPosition(end)
                    scanner.next()
                }
                return ParsedInline.of(
                    RawHtmlInlineNode(
                        name,
                        scanner.getSource(body, scanner.position()).content,
                        scanner.getSource(start, scanner.position()).content,
                    ),
                    scanner.position(),
                )
            }
        }
}

/** Convert data-only HTML into the same CommonMark nodes used by native Markdown rendering. */
internal class HtmlMarkdown(private val source: String, private val offset: Int) {
    private val parser = HtmlParser.htmlParser().setMaxDepth(128).setTrackPosition(true)

    fun parse(): Document =
        Document().also { root ->
            val body = Element("body")
            val nodes = parser.parseFragmentInput(source, body, "")
            appendBlocks(nodes, root)
        }

    private fun raw(node: HtmlNode): String {
        val start = node.sourceRange()
        val end = if (node is Element) node.endSourceRange() else start
        if (start.isTracked && end.isTracked) {
            val last = maxOf(start.endPos(), end.endPos())
            if (start.startPos() >= 0 && last <= source.length)
                return source.substring(start.startPos(), last)
        }
        return node.outerHtml()
    }

    private fun appendBlocks(nodes: List<HtmlNode>, parent: Node) {
        var paragraph: Paragraph? = null
        var inline: InlineWriter? = null
        fun flush() {
            paragraph = null
            inline = null
        }
        for (node in nodes) {
            val tag = (node as? Element)?.normalName()
            if (tag in blockTags && node is Element) {
                flush()
                when (tag) {
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6" ->
                        parent.appendChild(
                            Heading().apply {
                                level = tag.last().digitToInt()
                                InlineWriter().append(node.childNodes(), this)
                            },
                        )
                    "blockquote" ->
                        parent.appendChild(
                            BlockQuote().also { appendBlocks(node.childNodes(), it) },
                        )
                    "ul",
                    "ol" -> {
                        val list =
                            if (tag == "ul") BulletList().apply { marker = "-" }
                            else
                                OrderedList().apply {
                                    markerStartNumber = node.attr("start").toIntOrNull() ?: 1
                                    markerDelimiter = "."
                                }
                        parent.appendChild(list)
                        for (child in node.childNodes()) {
                            if (child is Element && child.normalName() == "li")
                                list.appendChild(
                                    ListItem().also { appendBlocks(child.childNodes(), it) },
                                )
                            else if (child !is TextNode || child.wholeText.isNotBlank())
                                list.appendChild(
                                    ListItem().also { appendBlocks(listOf(child), it) },
                                )
                        }
                    }
                    "pre" -> {
                        val content =
                            node.children().singleOrNull()?.takeIf { it.normalName() == "code" }
                                ?: node
                        val language =
                            content
                                .classNames()
                                .firstOrNull { it.startsWith("language-") }
                                ?.removePrefix("language-")
                                .orEmpty()
                        parent.appendChild(
                            FencedCodeBlock().apply {
                                literal = codeContent(content)
                                info = language
                            },
                        )
                    }
                    "table" -> {
                        for (caption in node.children().filter { it.normalName() == "caption" }) {
                            parent.appendChild(
                                Paragraph().also {
                                    InlineWriter().append(caption.childNodes(), it)
                                },
                            )
                        }
                        parent.appendChild(table(node))
                    }
                    "hr" -> parent.appendChild(ThematicBreak())
                    "details" ->
                        parent.appendChild(
                            DetailsNode(
                                    offset + node.sourceRange().startPos().coerceAtLeast(0),
                                    node.hasAttr("open"),
                                )
                                .also { appendBlocks(node.childNodes(), it) },
                        )
                    "summary" ->
                        parent.appendChild(
                            SummaryNode().also { appendBlocks(node.childNodes(), it) },
                        )
                    else -> appendBlocks(node.childNodes(), parent)
                }
            } else {
                if (paragraph == null && node is TextNode && node.wholeText.isBlank()) continue
                if (paragraph == null) {
                    paragraph = Paragraph().also(parent::appendChild)
                    inline = InlineWriter()
                }
                inline!!.append(listOf(node), paragraph!!)
            }
        }
    }

    private fun codeContent(element: Element): String = buildString {
        for (child in element.childNodes()) append(
            if (child is TextNode) child.wholeText else raw(child),
        )
    }

    private fun table(element: Element): TableBlock =
        TableBlock().also { table ->
            fun rows(nodes: List<HtmlNode>) {
                for (node in nodes) if (node is Element)
                    when (node.normalName()) {
                        "thead",
                        "tbody",
                        "tfoot" -> rows(node.childNodes())
                        "tr" -> {
                            val row = TableRow()
                            for (cell in node.children()) if (
                                cell.normalName() in setOf("th", "td")
                            )
                                row.appendChild(
                                    TableCell().apply {
                                        isHeader = cell.normalName() == "th"
                                        alignment =
                                            when (cell.attr("align").lowercase()) {
                                                "center" -> TableCell.Alignment.CENTER
                                                "right" -> TableCell.Alignment.RIGHT
                                                else -> TableCell.Alignment.LEFT
                                            }
                                        InlineWriter().append(cell.childNodes(), this)
                                    },
                                )
                            table.appendChild(TableBody().apply { appendChild(row) })
                        }
                    }
            }
            rows(element.childNodes())
        }

    private inner class InlineWriter {
        private var pendingSpace = false
        private var pendingBreak = false
        private var visible = false

        private fun beginContent(parent: Node) {
            if (pendingBreak) {
                if (visible) parent.appendChild(HardLineBreak())
                pendingSpace = false
                visible = false
                pendingBreak = false
            }
        }

        private fun opaque(node: HtmlNode, parent: Node) {
            beginContent(parent)
            if (pendingSpace && visible) parent.appendChild(Text(" "))
            pendingSpace = false
            visible = true
            parent.appendChild(
                RawHtmlInlineNode(
                    (node as? Element)?.normalName() ?: node.nodeName(),
                    "",
                    raw(node),
                ),
            )
        }

        private fun text(value: String, parent: Node) {
            if (value.any { it !in " \t\r\n\u000C" }) beginContent(parent)
            val output = StringBuilder()
            for (char in value) {
                if (char in " \t\r\n\u000C") pendingSpace = true
                else {
                    if (pendingSpace && visible) output.append(' ')
                    output.append(char)
                    pendingSpace = false
                    visible = true
                }
            }
            if (output.isNotEmpty()) parent.appendChild(Text(output.toString()))
        }

        fun append(nodes: List<HtmlNode>, parent: Node) {
            for (node in nodes) when (node) {
                is TextNode -> text(node.wholeText, parent)
                is Element -> {
                    val tag = node.normalName()
                    val child: Node? =
                        when (tag) {
                            "b",
                            "strong" -> StrongEmphasis()
                            "i",
                            "em" -> Emphasis()
                            "s",
                            "del",
                            "strike",
                            "u",
                            "ins",
                            "mark",
                            "sup",
                            "sub" -> null
                            "a" -> Link(node.attr("href"), node.attr("title"))
                            "img" ->
                                if (node.attr("src").isNotBlank()) HtmlImageNode(node.attributes())
                                else null
                            "code",
                            "kbd",
                            "samp" -> Code(codeContent(node))
                            "br" -> HardLineBreak()
                            else -> null
                        }
                    when {
                        child is Image || child is Code -> {
                            beginContent(parent)
                            if (pendingSpace && visible) parent.appendChild(Text(" "))
                            pendingSpace = false
                            visible = true
                            parent.appendChild(child)
                        }
                        child is HardLineBreak -> {
                            parent.appendChild(child)
                            pendingSpace = false
                            visible = false
                            pendingBreak = false
                        }
                        child != null -> {
                            parent.appendChild(child)
                            append(node.childNodes(), child)
                        }
                        tag in styledTags -> {
                            parent.appendChild(HtmlInline().apply { literal = "<$tag>" })
                            append(node.childNodes(), parent)
                            parent.appendChild(HtmlInline().apply { literal = "</$tag>" })
                        }
                        tag in blockTags -> {
                            pendingBreak = visible
                            append(node.childNodes(), parent)
                            pendingBreak = visible
                        }
                        else -> opaque(node, parent)
                    }
                }
                else -> opaque(node, parent)
            }
        }
    }

    companion object {
        private val styledTags = setOf("s", "del", "strike", "u", "ins", "mark", "sup", "sub")
        private val blockTags =
            setOf(
                "p",
                "div",
                "section",
                "article",
                "header",
                "footer",
                "main",
                "aside",
                "figure",
                "figcaption",
                "center",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "blockquote",
                "ul",
                "ol",
                "pre",
                "table",
                "hr",
                "details",
                "summary",
            )
    }
}

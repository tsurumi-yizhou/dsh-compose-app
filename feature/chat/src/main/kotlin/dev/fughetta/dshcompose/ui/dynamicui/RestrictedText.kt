package dev.fughetta.dshcompose.ui.dynamicui

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class UiTextSpan(val start: Int, val end: Int, val code: Boolean)

internal data class UiRichText(val text: String, val spans: PersistentList<UiTextSpan>)

/** Parsed on the document worker. Links and images retain only their visible words. */
internal object RestrictedText {
    private val parser = Parser.builder().maxOpenBlockParsers(64).maxInlineNesting(64).build()

    fun parse(source: String): UiRichText {
        val output = StringBuilder()
        val spans = mutableListOf<UiTextSpan>()
        fun append(node: Node) {
            val start = output.length
            when (node) {
                is Text -> output.append(node.literal)
                is Code -> output.append(node.literal)
                is FencedCodeBlock -> output.append(node.literal.trimEnd('\n'))
                is IndentedCodeBlock -> output.append(node.literal.trimEnd('\n'))
                is HtmlInline -> output.append(node.literal)
                is HtmlBlock -> output.append(node.literal)
                is SoftLineBreak -> output.append(' ')
                is HardLineBreak -> output.append('\n')
                else -> {
                    var child = node.firstChild
                    while (child != null) {
                        append(child)
                        child = child.next
                    }
                }
            }
            if (
                output.length > start &&
                    (node is Code ||
                        node is StrongEmphasis ||
                        node is FencedCodeBlock ||
                        node is IndentedCodeBlock)
            ) {
                spans.add(UiTextSpan(start, output.length, node !is StrongEmphasis))
            }
            if (node is Block && node !is Document && output.isNotEmpty() && output.last() != '\n')
                output.append('\n')
        }
        append(parser.parse(source))
        return UiRichText(output.toString().trimEnd('\n'), spans.toPersistentList())
    }
}

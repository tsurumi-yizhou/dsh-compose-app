package dev.fughetta.dshcompose.ui.dynamicui

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.markdown.*
import org.junit.Assert.*
import org.junit.Test

class BareUiAndResendTest {
    private fun parse(source: String) = MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty)

    @Test
    fun completeBareViewsKeepTheirDataAndSurroundingMarkdownAndCanBeAnswered() {
        val json =
            """
            {
              // punctuation belongs to the view
              "type":"stack", "title":"Decision",
              "children":[
                {"type":"text","text":"literal ${'$'}${'$'} <details>not a disclosure</details> ```"},

                {"type":"confirm","confirmLabel":"Start"},
              ],
            }
            """
                .trimIndent()
        val source = "Before\n$json\n\nAfter [reference][target].\n\n[target]: https://example.com"
        val document = parse(source)
        val visual = document.blocks.filterIsInstance<MarkdownBlock.Visual>().single()
        assertTrue(visual.closed)
        assertEquals(json, visual.source)
        assertEquals("Decision", visual.parsed!!.view!!.title)
        assertFalse(document.blocks.any { it is MarkdownBlock.Disclosure })
        val before = document.blocks.first() as MarkdownBlock.Text
        val after = document.blocks.last() as MarkdownBlock.Text
        assertEquals("Before", before.content.text)
        assertEquals(
            "https://example.com",
            after.content.marks.single { it.kind == MarkdownMarkKind.Link }.target,
        )
        val row = ChatItem("m1.1", ChatRole.Assistant, source, seq = 1)
        val reply =
            dynamicUiReplyText(
                row,
                json,
                "view.children[1]",
                emptyList(),
                null,
                true,
                UiReplyLabels("Select", "Other", "Confirm", "Cancel"),
            )
        assertEquals("[dsh-ui reply] Decision\nConfirm: Start", reply)
    }

    @Test
    fun codeMathScriptsInlineJsonAndUnrelatedObjectsNeverBecomeBareCards() {
        val json = """{"type":"confirm"}"""
        val examples =
            listOf(
                "```json\n$json\n```",
                "    $json",
                "`literal\n$json\nend`",
                "\$\$\n$json\n\$\$",
                "\\(\n$json\n\\)",
                "<script>\n$json\n</script>",
                "prefix <code>\n$json\n</code>",
                "Inline $json example.",
                "$json trailing text",
                "{\"settings\":\n$json\n}",
                "{\"type\":\"confirm\"",
            )
        for (source in examples) assertTrue(
            source,
            parse(source).blocks.none { it is MarkdownBlock.Visual },
        )
        val source = "{\"type\":\"confirm\""
        assertEquals(1, parse("$source}").blocks.filterIsInstance<MarkdownBlock.Visual>().size)
        assertTrue(
            MarkdownDocuments.parse("$source}", ReferenceLabelIndex.Empty, allowBareViews = false)
                .blocks
                .none { it is MarkdownBlock.Visual },
        )
    }

    @Test
    fun disclosureContainsOneBareViewWhileJsonTagsRemainDataAndEarlierSnapshotsStayUnchanged() {
        val json =
            """{"type":"text","text":"<details><summary>literal</summary></details> ${'$'}${'$'}"}"""
        val source = "<details><summary>Actual disclosure</summary>\n\n$json\n\n</details>"
        val document = parse(source)
        val disclosure = document.blocks.single() as MarkdownBlock.Disclosure
        assertEquals("Actual disclosure", disclosure.title!!.text)
        val view = disclosure.blocks.single() as MarkdownBlock.Visual
        assertEquals(json, view.source)
        assertNotNull(view.parsed!!.view)
        val continued = parse("$source\n\n{\"type\":\"metric\",\"label\":\"Count\",\"value\":2}")
        assertEquals(2, continued.blocks.size)
        assertEquals(1, document.blocks.size)
        assertEquals(1, disclosure.blocks.size)
    }

    @Test
    fun resendUsesCurrentClosedInvalidCardAndDoesNotEchoItsSourceOrAllowValidOrDialectCards() {
        val source = """{"type":"unknown-component","private":"never echo this"}"""
        val row = ChatItem("m1.1", ChatRole.Assistant, "```dsh-ui\n$source\n```", seq = 1)
        val text =
            dynamicUiResendText(row, source, { "Unknown component" }) {
                "Fix the previous view: $it"
            }!!
        assertTrue(text.contains("view.type: Unknown component"))
        assertFalse(text.contains("never echo this"))
        assertFalse(text.contains("unknown-component"))
        assertNotNull(
            dynamicUiResendText(
                row.copy(text = "```dsh-ui\n\n```"),
                "",
                { "Invalid JSON" },
                { it },
            ),
        )
        assertNull(
            dynamicUiResendText(row.copy(text = "```dsh-ui\n$source"), source, { "Error" }, { it }),
        )
        assertNull(
            dynamicUiResendText(
                row.copy(text = "```json\n$source\n```"),
                source,
                { "Error" },
                { it },
            ),
        )
        val valid = """{"type":"confirm"}"""
        assertNull(
            dynamicUiResendText(
                row.copy(text = "```dsh-ui\n$valid\n```"),
                valid,
                { "Error" },
                { it },
            ),
        )
        val dialect = """{"title":"Genui","items":[{"type":"button","label":"Never send"}]}"""
        assertEquals("genui", DynamicUiParser.parse(dialect).dialect)
        assertNull(
            dynamicUiResendText(
                row.copy(text = "```dsh-ui\n$dialect\n```"),
                dialect,
                { "Error" },
                { it },
            ),
        )
    }
}

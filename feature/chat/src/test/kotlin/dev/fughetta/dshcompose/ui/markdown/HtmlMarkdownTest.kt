package dev.fughetta.dshcompose.ui.markdown

import dev.fughetta.dshcompose.ui.composer.*
import org.junit.Assert.*
import org.junit.Test

class HtmlMarkdownTest {
    private fun parse(source: String) = MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty)

    @Test
    fun htmlAndMarkdownImagesShareGalleryOrderAndKeepDecodedAddressesAltAndSize() {
        val document =
            parse(
                """
                ![first](one.png)

                Before <a href='https://example.com/?a=1&amp;b=2'>link</a> <IMG SRC='plots/a&amp;b.png' ALT='second &amp; two' WIDTH='120' height=80 onerror='alert(1)'> after.

                <div>
                  <p>Third <img src="three.svg" alt="third"></p>
                  <blockquote><img src="four.png" alt="fourth"></blockquote>
                  <details><summary>Hidden</summary><p><img src="five.png" alt="fifth"></p></details>
                </div>

                ![last](last.png)
                """
                    .trimIndent(),
            )
        assertEquals(
            listOf("first", "second & two", "third", "fourth", "fifth", "last"),
            document.images.map { it.alt },
        )
        assertEquals("plots/a&b.png", document.images[1].address)
        val paragraph = (document.blocks[1] as MarkdownBlock.Text).content
        assertEquals("Before link second & two after.", paragraph.text)
        val image = paragraph.marks.single { it.kind == MarkdownMarkKind.Image }
        assertEquals(HtmlImageSize(120f, 80f), image.imageSize)
        assertEquals(1, image.imageIndex)
        assertEquals(
            "https://example.com/?a=1&b=2",
            paragraph.marks.single { it.kind == MarkdownMarkKind.Link }.target,
        )
        val hidden = document.blocks.filterIsInstance<MarkdownBlock.Disclosure>().single()
        assertFalse(hidden.initiallyOpen)
        assertEquals("Hidden", hidden.title!!.text)
        assertEquals(
            4,
            (hidden.blocks.single() as MarkdownBlock.Text)
                .content
                .marks
                .single { it.kind == MarkdownMarkKind.Image }
                .imageIndex,
        )
    }

    @Test
    fun htmlBlocksUseNativeHeadingsListsQuotesTablesAndLiteralCode() {
        val document =
            parse(
                """
                <div>
                  <h2><b>Report</b></h2>
                  <p>**literal** &amp; <u>under</u> H<sub>2</sub>O<br>next</p>
                  <ol start="3"><li>First<ul><li>Nested</li></ul></li><li>Second</li></ol>
                  <blockquote><p>Quoted <em>words</em></p></blockquote>
                  <table><caption>Values</caption><thead><tr><th>Name</th><th align="right">Value</th></tr></thead><tbody><tr><td><p>Alpha</p><p>Beta</p></td><td>42</td></tr></tbody></table>
                  <pre><code class="language-html">&lt;img src="literal.png"&gt;
                @src/untouched.kt</code></pre>
                </div>
                """
                    .trimIndent(),
            )
        val heading = document.blocks.first() as MarkdownBlock.Text
        assertEquals(2, heading.heading)
        assertEquals("Report", heading.content.text)
        assertEquals(MarkdownMarkKind.Strong, heading.content.marks.single().kind)
        val paragraph = (document.blocks[1] as MarkdownBlock.Text).content
        assertEquals("**literal** & under H2O\nnext", paragraph.text)
        assertTrue(paragraph.marks.any { it.kind == MarkdownMarkKind.Underline })
        assertTrue(paragraph.marks.any { it.kind == MarkdownMarkKind.Subscript })
        val entries = document.blocks.filterIsInstance<MarkdownBlock.ListEntry>()
        assertEquals(listOf("3.", "4."), entries.map { it.marker })
        assertEquals("•", (entries.first().blocks[1] as MarkdownBlock.ListEntry).marker)
        assertTrue(document.blocks.any { it is MarkdownBlock.Quote })
        val table = document.blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(
            listOf(listOf("Name", "Value"), listOf("Alpha\nBeta", "42")),
            table.rows.map { row -> row.map { it.content.text } },
        )
        assertTrue(
            document.blocks.filterIsInstance<MarkdownBlock.Text>().any {
                it.content.text == "Values"
            },
        )
        assertTrue(table.rows.first().all { it.header })
        assertEquals(TableAlignment.Right, table.rows.first()[1].alignment)
        val code = document.blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("html", code.language)
        assertTrue(code.text.startsWith("<img src=\"literal.png\">\n"))
        assertTrue(code.text.contains("@src/untouched.kt"))
        assertTrue(document.images.isEmpty())
    }

    @Test
    fun codeScriptsCommentsAndUnknownElementsCannotCreateImagesOrDisclosures() {
        val code =
            "<img src=\"code.png\"> **literal** ${'$'}x_1${'$'} @src/file.kt <details>literal</details>"
        val script =
            "<SCRIPT data-note='>'>const value = '<img src=\"script.png\"><details>text</details> @src/raw.kt';</SCRIPT>"
        val document =
            parse(
                """
            Before <code>$code</code> after.

            Before $script after.

            <div><script>let x = '<img src="nested.png">';</script><unknown><img src="unknown.png"></unknown><!-- <img src="comment.png"> --></div>

            ```html
            <img src="fence.png">
            ```
        """
                    .trimIndent(),
            )
        assertTrue(document.images.isEmpty())
        assertFalse(document.blocks.any { it is MarkdownBlock.Disclosure })
        val first = (document.blocks.first() as MarkdownBlock.Text).content
        assertTrue(first.text.contains(code))
        assertEquals(listOf(MarkdownMarkKind.Code), first.marks.map { it.kind })
        val second = (document.blocks[1] as MarkdownBlock.Text).content
        assertTrue(second.text.contains(script))
        assertFalse(second.marks.any { it.kind == MarkdownMarkKind.Reference })
        val third = (document.blocks[2] as MarkdownBlock.Text).content
        assertTrue(third.text.contains("<unknown><img src=\"unknown.png\"></unknown>"))
        assertTrue(third.text.contains("<!-- <img src=\"comment.png\"> -->"))
        val pendingScript = parse("Before <script>const value = '<img src=\"pending.png\">'")
        assertTrue(pendingScript.images.isEmpty())
    }

    @Test
    fun partialHtmlReparsesWithoutLeakingImagesFromEarlierSnapshotsOrLosingInvalidSources() {
        val source = "Before <img src='a.png' alt='first'"
        assertTrue(parse(source).images.isEmpty())
        val completed = parse("$source width='NaN' height='-2'> after <img alt='missing'>")
        assertEquals(listOf("a.png"), completed.images.map { it.address })
        val content = (completed.blocks.single() as MarkdownBlock.Text).content
        assertEquals(
            HtmlImageSize(null, null),
            content.marks.single { it.kind == MarkdownMarkKind.Image }.imageSize,
        )
        assertTrue(content.text.contains("<img alt='missing'>"))
        val partial = parse("<div><p>First <img src='first.png' alt='one'>")
        val next =
            parse(
                "<div><p>First <img src='first.png' alt='one'></p><p><img src='second.png' alt='two'></p></div>",
            )
        assertEquals(1, partial.images.size)
        assertEquals(2, next.images.size)
        assertTrue(parse("<img src='   ' alt='blank'>").images.isEmpty())
    }
}

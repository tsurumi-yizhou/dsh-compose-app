package dev.fughetta.dshcompose.ui.markdown

import dev.fughetta.dshcompose.ui.composer.*
import org.junit.Assert.*
import org.junit.Test

class DisclosureParserTest {
    @Test
    fun nestedDisclosuresKeepMarkdownLinkDefinitionsAndImageOrderWithOnlyMountedImagesInTheGallery() {
        val source =
            """
            ![before](before.png)

            <details title="open is just a title">
            <summary><strong>Status</strong> **now** [see][reference]</summary>

            Body with `literal` and @folder/file.kt. H<sub>2</sub>O, x<sup>2</sup>, <u>under</u>, <mark>marked</mark>, <kbd>@raw/path.kt</kbd>.

            - first
            - second

            ![hidden](hidden.png)

            <details OPEN="false"><summary>Nested</summary>Inner ![nested](nested.png)</details>

            </details>

            ![after](after.png)

            [reference]: https://example.com/report
            """
                .trimIndent()
        val document = MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty)
        val outer = document.blocks.filterIsInstance<MarkdownBlock.Disclosure>().single()
        assertFalse(outer.initiallyOpen)
        assertEquals(source.indexOf("<details"), outer.position)
        assertEquals("Status now see", outer.title!!.text)
        assertEquals(
            "https://example.com/report",
            outer.title.marks.single { it.kind == MarkdownMarkKind.Link }.target,
        )
        assertEquals(2, outer.title.marks.count { it.kind == MarkdownMarkKind.Strong })
        val body = (outer.blocks.first() as MarkdownBlock.Text).content
        assertTrue(body.text.contains("H2O, x2, under, marked, @raw/path.kt"))
        assertTrue(body.marks.any { it.kind == MarkdownMarkKind.Subscript })
        assertTrue(body.marks.any { it.kind == MarkdownMarkKind.Superscript })
        assertTrue(body.marks.any { it.kind == MarkdownMarkKind.Underline })
        assertTrue(body.marks.any { it.kind == MarkdownMarkKind.Highlight })
        assertTrue(
            body.marks.any {
                it.kind == MarkdownMarkKind.Code &&
                    body.text.substring(it.start, it.end) == "@raw/path.kt"
            },
        )
        assertEquals(2, outer.blocks.filterIsInstance<MarkdownBlock.ListEntry>().size)
        val nested = outer.blocks.filterIsInstance<MarkdownBlock.Disclosure>().single()
        assertTrue(nested.initiallyOpen)
        assertEquals("Nested", nested.title!!.text)
        assertTrue((nested.blocks.first() as MarkdownBlock.Text).content.text.startsWith("Inner"))
        assertEquals(listOf("before", "hidden", "nested", "after"), document.images.map { it.alt })
        val selected = replyGallerySelection(document.images, listOf(3, 0), 3)!!
        assertEquals(listOf("before", "after"), selected.images.map { it.alt })
        assertEquals(1, selected.initialIndex)
        val streaming =
            MarkdownDocuments.parse(
                "<details open><summary>Working</summary>Still writing **body**",
                ReferenceLabelIndex.Empty,
            )
        val pending = streaming.blocks.single() as MarkdownBlock.Disclosure
        assertTrue(pending.initiallyOpen)
        assertEquals("Working", pending.title!!.text)
        assertTrue(
            (pending.blocks.single() as MarkdownBlock.Text)
                .content
                .text
                .contains("Still writing body"),
        )
    }

    @Test
    fun codeAndRawScriptsKeepLiteralTagsWhileQuotedAndListDisclosuresKeepTheirContainers() {
        val source =
            """
            `<details><summary>inline</summary></details>`

            ```html
            <details>literal fence</details>
            ```

            <script>const text = "<details>script</details>";</script>

            > <details><summary>Quoted</summary>
            > ```html
            > <details>quoted code</details>
            > ```
            > </details>

            - <details><summary>Item</summary>

              Item body
              </details>

            Outside
            """
                .trimIndent()
        val document = MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty)
        assertTrue(
            (document.blocks.first() as MarkdownBlock.Text).content.text.contains("<details>"),
        )
        assertEquals(
            "<details>literal fence</details>\n",
            document.blocks.filterIsInstance<MarkdownBlock.Code>().single().text,
        )
        assertTrue(
            document.blocks.filterIsInstance<MarkdownBlock.Text>().any {
                it.content.text.contains("<script>")
            },
        )
        val quoted =
            document.blocks.filterIsInstance<MarkdownBlock.Quote>().single().blocks.single()
                as MarkdownBlock.Disclosure
        assertEquals("Quoted", quoted.title!!.text)
        assertEquals(
            "<details>quoted code</details>\n",
            (quoted.blocks.single() as MarkdownBlock.Code).text,
        )
        val item =
            document.blocks.filterIsInstance<MarkdownBlock.ListEntry>().single().blocks.single()
                as MarkdownBlock.Disclosure
        assertEquals("Item", item.title!!.text)
        assertEquals("Item body", (item.blocks.single() as MarkdownBlock.Text).content.text)
        assertEquals("Outside", (document.blocks.last() as MarkdownBlock.Text).content.text)
        assertFalse(document.blocks.toString().contains("\uE000"))
    }
}

package dev.fughetta.dshcompose.ui.markdown

import dev.fughetta.dshcompose.ui.composer.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    @Test
    fun galleryKeepsRenderedImageOrderAcrossNestedContentAndFreezesTheOpenedReply() {
        val source =
            """
            @folder/long-name.kt ![first](first.png)

            > ![second](repeat.png)

            - ![third](repeat.png)

            | Preview |
            | --- |
            | ![fourth](fourth.svg) |

            ```markdown
            ![not an image](code.png)
            ```
            """
                .trimIndent()
        val document = MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty)
        assertEquals(listOf("first", "second", "third", "fourth"), document.images.map { it.alt })
        assertEquals(
            listOf("first.png", "repeat.png", "repeat.png", "fourth.svg"),
            document.images.map { it.address },
        )
        fun indices(blocks: List<MarkdownBlock>): List<Int> = blocks.flatMap { block ->
            when (block) {
                is MarkdownBlock.Text -> block.content.marks.mapNotNull { it.imageIndex }
                is MarkdownBlock.Quote -> indices(block.blocks)
                is MarkdownBlock.ListEntry -> indices(block.blocks)
                is MarkdownBlock.Table ->
                    block.rows.flatMap { row ->
                        row.flatMap { it.content.marks.mapNotNull { mark -> mark.imageIndex } }
                    }
                else -> emptyList()
            }
        }
        assertEquals(listOf(0, 1, 2, 3), indices(document.blocks))
        val selected = ReplyGallerySelection(document.images, 2)
        val continued =
            MarkdownDocuments.parse("$source\n\n![fifth](fifth.png)", ReferenceLabelIndex.Empty)
        assertEquals(5, continued.images.size)
        assertEquals(4, selected.images.size)
        assertEquals("third", selected.images[selected.initialIndex].alt)
    }

    @Test
    fun assistantDocumentKeepsStructureStylesReferencesAndLiteralCodeSeparate() {
        val source =
            """
            # **Report**

            Read **@src/main.kt** and @[task](dsh-session:id), @project plan. Use `@src/raw.kt` and [the file @src/raw.kt](https://example.com).

            > A *quoted* answer.

            3. First
               - Nested
            4. Second

            - [x] Done
            - [ ] Pending

            | Item | Result |
            | :--- | ---: |
            | ~~old~~ | **good** |

            ```kotlin
            val path = "@src/main.kt"
            ```

            ---
            """
                .trimIndent()
        val document =
            MarkdownDocuments.parse(source, ReferenceLabelIndex.build(sequenceOf("project plan")))
        val heading = document.blocks.first() as MarkdownBlock.Text
        assertEquals(1, heading.heading)
        assertEquals("Report", heading.content.text)
        assertEquals(MarkdownMarkKind.Strong, heading.content.marks.single().kind)
        val body = document.blocks[1] as MarkdownBlock.Text
        assertFalse(body.content.text.contains("dsh-session:"))
        assertTrue(body.content.text.contains("@main.kt"))
        assertFalse(body.content.text.contains("`"))
        assertTrue(body.content.text.contains("Use @src/raw.kt and the file @src/raw.kt"))
        assertEquals(
            listOf(" @main.kt ", " @project plan ", "@task"),
            body.content.marks
                .filter { it.kind == MarkdownMarkKind.Reference }
                .map { body.content.text.substring(it.start, it.end) }
                .sorted(),
        )
        assertEquals(
            "https://example.com",
            body.content.marks.single { it.kind == MarkdownMarkKind.Link }.target,
        )
        assertTrue(document.blocks.any { it is MarkdownBlock.Quote })
        val entries = document.blocks.filterIsInstance<MarkdownBlock.ListEntry>()
        assertEquals(listOf("3.", "4.", "☑", "☐"), entries.map { it.marker })
        assertEquals("•", (entries.first().blocks[1] as MarkdownBlock.ListEntry).marker)
        val table = document.blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(2, table.rows.size)
        assertTrue(table.rows.first().all { it.header })
        assertEquals(TableAlignment.Right, table.rows[1][1].alignment)
        assertEquals(MarkdownMarkKind.Strike, table.rows[1][0].content.marks.single().kind)
        val code = document.blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val path = \"@src/main.kt\"\n", code.text)
        assertEquals(MarkdownBlock.Rule, document.blocks.last())
    }

    @Test
    fun partialStreamAndUnrenderedMediaKeepTheirContentsAndLinksRequireExplicitHandling() {
        val partial = "An **answer**\n\n```kotlin\nval answer = 42"
        val blocks = MarkdownDocuments.parse(partial, ReferenceLabelIndex.Empty).blocks
        assertEquals("val answer = 42\n", (blocks.last() as MarkdownBlock.Code).text)
        val completed =
            MarkdownDocuments.parse(
                "$partial\n```\n\n![diagram](https://example.com/image.png) ![](https://example.com/empty.png)\n\n<script>alert(1)</script>",
                ReferenceLabelIndex.Empty,
            )
        val imageText = (completed.blocks[2] as MarkdownBlock.Text).content
        assertEquals("diagram \uFFFC", imageText.text)
        assertEquals(2, imageText.marks.count { it.kind == MarkdownMarkKind.Image })
        assertEquals("https://example.com/empty.png", imageText.marks.last().target)
        assertTrue(
            (completed.blocks.last() as MarkdownBlock.Text)
                .content
                .text
                .contains("<script>alert(1)</script>"),
        )
        assertTrue(externalMarkdownLink("https://example.com/path?q=1"))
        assertTrue(externalMarkdownLink("mailto:person@example.com"))
        assertFalse(externalMarkdownLink("javascript:alert(1)"))
        assertFalse(externalMarkdownLink("intent://open#Intent;end"))
        assertFalse(externalMarkdownLink("file:///private/file"))
    }
}

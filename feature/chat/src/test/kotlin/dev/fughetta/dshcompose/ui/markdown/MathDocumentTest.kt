package dev.fughetta.dshcompose.ui.markdown

import dev.fughetta.dshcompose.ui.composer.*
import org.junit.Assert.*
import org.junit.Test

class MathDocumentTest {
    private fun parse(source: String) = MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty)

    private fun formulae(content: MarkdownText) = content.marks.mapNotNull { it.formula }

    @Test
    fun pricesEscapesAndCodeStayLiteralWhileEquationsKeepTheirTex() {
        val literal =
            listOf(
                "\$5 and \$10",
                "\$5-\$10",
                "5\$ 和 10\$",
                "\$5 and 10\$",
                "\$ x\$",
                "\$x \$",
                "\$x`raw`\$",
                "\\\$5",
            )
        for (source in literal) {
            val content = (parse(source).blocks.single() as MarkdownBlock.Text).content
            assertTrue(source, formulae(content).isEmpty())
        }
        val source =
            """Use ${'$'}x_i^2 + 1${'$'}, \(\frac{a}{b}\), and `${'$'}not_math${'$'}`.

```tex
${'$'}${'$'}literal${'$'}${'$'}
```
"""
        val document = parse(source)
        val body = (document.blocks.first() as MarkdownBlock.Text).content
        assertEquals(listOf("x_i^2 + 1", "\\frac{a}{b}"), formulae(body).map { it.source })
        assertTrue(formulae(body).all { it.renderable && !it.display })
        assertEquals("\$\$literal\$\$\n", (document.blocks.last() as MarkdownBlock.Code).text)
        val multilineCode =
            (parse("`literal\n\$\$x_1\$\$\n\\[not math\\]\nend`").blocks.single()
                    as MarkdownBlock.Text)
                .content
        assertTrue(formulae(multilineCode).isEmpty())
        assertEquals(MarkdownMarkKind.Code, multilineCode.marks.single().kind)
    }

    @Test
    fun multilineDisplayRetainsBlankLinesTrailingProseAndGlobalLinkDefinitions() {
        val document =
            parse(
                """
                ${'$'}${'$'}
                \begin{aligned}
                a &= 1 \\

                b &= 2
                \end{aligned}
                ${'$'}${'$'} after [reference][target]

                [target]: https://example.com
                """
                    .trimIndent(),
            )
        val equation = (document.blocks.first() as MarkdownBlock.Math).formula
        assertTrue(equation.complete)
        assertTrue(equation.source.contains("\n\nb"))
        val tail = (document.blocks[1] as MarkdownBlock.Text).content
        assertTrue(tail.text.contains("after reference"))
        assertEquals(
            "https://example.com",
            tail.marks.single { it.kind == MarkdownMarkKind.Link }.target,
        )
        val mixed =
            (parse("before ${'$'}${'$'}x_1${'$'}${'$'} after").blocks.single()
                    as MarkdownBlock.Text)
                .content
        assertTrue(formulae(mixed).single().display)
        val quote = parse("> \\[\n> x^2\n> \\]").blocks.single() as MarkdownBlock.Quote
        assertEquals("x^2", (quote.blocks.single() as MarkdownBlock.Math).formula.source)
        val prefixed = parse("Equation: \$\$\na = 1\n\nb = 2\n\$\$ next")
        assertEquals(
            "Equation:",
            (prefixed.blocks.first() as MarkdownBlock.Text).content.text.trim(),
        )
        assertEquals("a = 1\n\nb = 2", (prefixed.blocks[1] as MarkdownBlock.Math).formula.source)
        assertEquals("next", (prefixed.blocks[2] as MarkdownBlock.Text).content.text.trim())
    }

    @Test
    fun formulaBodiesAreProtectedInsideDisclosuresHeadingsAndTables() {
        val document =
            parse(
                """
                <details><summary>Value ${'$'}x_1${'$'}</summary>

                # Result ${'$'}y_2${'$'}

                | Math |
                | --- |
                | ${'$'}\text{@src/raw.kt <details>literal</details>}${'$'} |

                </details>
                """
                    .trimIndent(),
            )
        val disclosure = document.blocks.single() as MarkdownBlock.Disclosure
        assertEquals("x_1", formulae(disclosure.title!!).single().source)
        val heading = disclosure.blocks.first() as MarkdownBlock.Text
        assertEquals(1, heading.heading)
        assertEquals("y_2", formulae(heading.content).single().source)
        val cell = (disclosure.blocks[1] as MarkdownBlock.Table).rows[1][0].content
        assertEquals(
            "\\text{@src/raw.kt <details>literal</details>}",
            formulae(cell).single().source,
        )
        assertFalse(cell.marks.any { it.kind == MarkdownMarkKind.Reference })
        val display = parse("Equation: \$\$\n\n\\text{<details>literal</details>}\n\$\$")
        assertEquals(
            "\\text{<details>literal</details>}",
            (display.blocks[1] as MarkdownBlock.Math).formula.source,
        )
        assertFalse(display.blocks.any { it is MarkdownBlock.Disclosure })
    }

    @Test
    fun streamingInvalidAndOversizedFormulasKeepReadableSourceWithoutConsumingCode() {
        val document = parse("\$\$\\frac{a}{\n\n```kotlin\nval x = 1\n```")
        val pending = (document.blocks.first() as MarkdownBlock.Math).formula
        assertFalse(pending.complete)
        assertFalse(pending.renderable)
        assertEquals("\\frac{a}{", pending.source)
        assertEquals("val x = 1\n", (document.blocks.last() as MarkdownBlock.Code).text)
        val completed =
            (parse("\$\$\\frac{a}{b}\$\$").blocks.single() as MarkdownBlock.Math).formula
        assertTrue(completed.renderable)
        val broken = (parse("\$\$\\frac{a}{\$\$").blocks.single() as MarkdownBlock.Math).formula
        assertTrue(broken.complete)
        assertFalse(broken.renderable)
        assertEquals("\\frac{a}{", broken.source)
        assertEquals("\$\$", (parse("\$\$").blocks.single() as MarkdownBlock.Text).content.text)
        assertFalse(markdownFormula("{".repeat(65) + "x" + "}".repeat(65), true, true).renderable)
        assertFalse(markdownFormula("x".repeat(8193), true, true).renderable)
        assertFalse(markdownFormula("\\sqrt".repeat(129) + "x", true, true).renderable)
    }
}

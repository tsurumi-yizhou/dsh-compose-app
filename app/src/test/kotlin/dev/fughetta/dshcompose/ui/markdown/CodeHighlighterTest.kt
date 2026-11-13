package dev.fughetta.dshcompose.ui.markdown

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodeHighlighterTest {
    @Test
    fun existingLanguageFamiliesProduceUtf16SpansAndCodeIsDataRatherThanAnExecutableProgram() =
        runBlocking {
            CodeHighlighter().use { syntax ->
                val kotlin = "/* first\n second */\nval face = \"😀\""
                val tokens = syntax.tokens(kotlin, "kts")
                assertTrue("Kotlin lexer must produce spans", tokens.isNotEmpty())
                assertTrue(
                    tokens.any {
                        it.scope == "comment" &&
                            kotlin.substring(it.start, it.end).contains("second")
                    },
                )
                assertTrue(
                    tokens.any {
                        it.scope == "keyword" && kotlin.substring(it.start, it.end) == "val"
                    },
                )
                assertTrue(
                    tokens.any {
                        it.scope == "string" && kotlin.substring(it.start, it.end).contains("😀")
                    },
                )
                assertTrue(
                    tokens.all { it.start >= 0 && it.end <= kotlin.length && it.start < it.end },
                )
                assertTrue(tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
                val lines = CodeLayout.plain(kotlin).coloured(tokens)
                assertEquals("comment", lines.tokens(0).single().scope)
                assertEquals("comment", lines.tokens(1).single().scope)
                assertEquals(0, lines.tokens(1).single().start)
                assertEquals(" second */".length, lines.tokens(1).single().end)
                assertTrue(
                    syntax.tokens("SELECT value FROM records WHERE id = 42", "postgresql").any {
                        it.scope == "keyword"
                    },
                )
                assertTrue(
                    syntax.tokens("key: true\nitems: [1, 2]", "yml").any { it.scope == "attr" },
                )
                assertTrue(syntax.tokens("<div class=\"item\">hello</div>", "html").isNotEmpty())
                assertTrue(
                    syntax.tokens("<template>{{ true ? count : 1 }}</template>", "vue").any {
                        it.scope == "literal"
                    },
                )
                syntax.tokens("\"); globalThis.syntaxTokens = () => \"[]\"; //", "js")
                assertTrue(syntax.tokens("const answer = 42;", "js").any { it.scope == "keyword" })
                assertTrue(syntax.tokens("plain text", "unknown-language").isEmpty())
                assertTrue(syntax.tokens("x".repeat(20_001), "js").isEmpty())
            }
        }

    @Test
    fun longOutputsHaveAddressableRowsAndWideUnicodeLinesWrapWithoutChangingTheOriginalSource() {
        val source = buildString { repeat(100_000) { append("value ").append(it).append('\n') } }
        val layout = CodeLayout.plain(source)
        assertEquals(100_000, layout.rowCount)
        assertEquals("value 99999", layout.text(99_999))
        assertSame(source, layout.source)
        val wide = "a".repeat(1023) + "😀" + "b".repeat(2048)
        val wrapped = CodeLayout.plain(wide)
        assertTrue(wrapped.rowCount > 1)
        assertTrue(wrapped.columns <= 1024)
        assertFalse(wrapped.continuation(0))
        assertTrue(wrapped.continuation(1))
        assertEquals(wide, (0 until wrapped.rowCount).joinToString("") { wrapped.text(it) })
        for (row in 0 until wrapped.rowCount) {
            assertFalse(wrapped.text(row).first().isLowSurrogate())
            assertFalse(wrapped.text(row).last().isHighSurrogate())
        }
        val emptyLines = CodeLayout.plain("first\r\n\r\nlast\n")
        assertEquals(
            listOf("first", "", "last"),
            (0 until emptyLines.rowCount).map(emptyLines::text),
        )
    }
}

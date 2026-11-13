package dev.fughetta.dshcompose.domain.messages

import org.junit.Assert.*
import org.junit.Test

class ReasoningTranslationTest {
    @Test
    fun translationWindowsPreserveSeparatorsAndKeepTheLiveTailOriginal() {
        val source = "English paragraph 😀 with identifiers.\r\n \r\n中文段落 😀\n\nGrowing tail"
        val streaming = reasoningFragments(source, true, 12)
        assertEquals(source, streaming.joinToString("") { it.text + it.suffix })
        assertFalse(streaming.last().complete)
        assertTrue(streaming.dropLast(1).all { it.complete })
        assertTrue(streaming.all { it.text.length <= 12 })
        assertTrue(
            streaming.all {
                it.text.isEmpty() ||
                    (!it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate())
            },
        )
        val ended = reasoningFragments(source, false, 12)
        assertTrue(ended.all { it.complete })
        assertEquals(source, ended.joinToString("") { it.text + it.suffix })
        assertTrue(reasoningNeedsTranslation("This reasoning is in English", "zh"))
        assertFalse(reasoningNeedsTranslation("这是中文推理 abc", "zh"))
        assertTrue(reasoningNeedsTranslation("한국어 추론", "en"))
    }
}

package dev.fughetta.dshcompose.ui.composer

import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ComposerSkillTest {
    @Test
    fun insertionPreservesSelectionsReferencesAndWholeTokens() {
        val ref = ReferenceSpan(4, 12, "file", ReferenceSpanKind.File)
        val text = "ask @foo.txt next"
        val inserted = insertComposerSkill(text, 7, "review", persistentListOf(ref))
        assertEquals("ask @foo.txt /review next", inserted.input.text)
        assertEquals(ref, inserted.spans.single())
        assertEquals(inserted.input.text.indexOf("next"), inserted.input.end)
        val before = insertComposerSkill(text, 0, "review", persistentListOf(ref))
        assertEquals("/review ask @foo.txt next", before.input.text)
        assertEquals(12, before.spans.single().start)
        val repeated =
            insertComposerSkill(before.input.text, before.input.text.length, "review", before.spans)
        assertEquals(before.input.text, repeated.input.text)
        assertEquals(8, repeated.input.end)
        assertEquals(before.spans, repeated.spans)
        assertEquals(
            "prefix/review /review ",
            insertComposerSkill("prefix/review", null, "review").input.text,
        )
        assertEquals("/review ", insertComposerSkill("", null, "review").input.text)
        assertEquals("😀 /工具 rest", insertComposerSkill("😀 rest", 1, "工具").input.text)
        val spaced = insertComposerSkill("/review\nrest", 12, "review")
        assertEquals("/review\nrest", spaced.input.text)
        assertEquals(8, spaced.input.end)
    }
}

package dev.fughetta.dshcompose.domain.presets

import org.junit.Assert.*
import org.junit.Test

class PresetPolicyTest {
    @Test
    fun compositionKeepsSourceRowsWhileExposingOnlySwitchableModules() {
        val rows =
            listOf(
                PresetRow("persona", PERSONA_MODULE, "persona source"),
                PresetRow("tool-web", "@deepseek-ai/dsh-tool-web", "web source", disabled = true),
                PresetRow(
                    "tool-pwsh",
                    "@deepseek-ai/dsh-tool-pwsh",
                    "platform source",
                    condition = "process.platform !== 'win32'",
                ),
                PresetRow(
                    "skills",
                    "cordis:group",
                    "skills source",
                    children = listOf("@deepseek-ai/dsh-skill"),
                ),
                PresetRow("unparsed", "", "unparsed source"),
            )
        val composition = PresetComposition(rows, "User prompt", false) { _, _, _ -> "" }
        assertTrue(composition.hasPersona)
        assertEquals(listOf("tool-web", "skills"), composition.switchable.map { it.id })
        assertEquals(setOf("persona", "tool-pwsh", "skills", "unparsed"), composition.enabled)
        assertEquals(PresetRowKind.Skill, composition.switchable.last().kind)
        assertSame(rows, composition.rows)
        assertEquals("User prompt", composition.prompt)
    }

    @Test
    fun generatedIdsAvoidExistingPresetsAndRemainWithinTheIdentifierLimit() {
        val stem = presetIdFromName("Code Review")
        assertEquals("code-review", stem)
        assertEquals("code-review-3", freePresetId(stem, setOf("code-review", "code-review-2")))
        val full = "x".repeat(40)
        val duplicate = freePresetId(full + "-copy", setOf(full))
        assertTrue(validPresetId(duplicate))
        assertFalse(duplicate == full)
        assertEquals(40, duplicate.length)
    }
}

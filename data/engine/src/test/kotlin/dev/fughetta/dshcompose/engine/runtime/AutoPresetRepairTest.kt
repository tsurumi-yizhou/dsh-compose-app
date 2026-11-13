package dev.fughetta.dshcompose.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPresetRepairTest {
    private fun fix(text: String) = AutoPresetRepair.withoutAutoDefaultPreset(text.trimIndent())

    @Test
    fun dropsConfigHoldingOnlyTheAutoDefault() {
        assertEquals(
            "- id: model\n  config:\n    name: x\n- id: permission\n",
            fix(
                """
                - id: model
                  config:
                    name: x
                - id: permission
                  config:
                    defaultPreset: auto
                """,
            ) + "\n",
        )
    }

    @Test
    fun keepsOtherPermissionFields() {
        assertEquals(
            "- id: permission\n  config:\n    rules: []",
            fix(
                """
                - id: permission
                  config:
                    defaultPreset: "auto" # old
                    rules: []
                """,
            ),
        )
    }

    @Test
    fun leavesOtherDefaultsAndLayoutsAlone() {
        assertNull(fix("- id: permission\n  config:\n    defaultPreset: ask"))
        assertNull(fix("- id: permission\n  config: {defaultPreset: auto}"))
        assertNull(fix("- id: permission\n  insert: true\n  config:\n    defaultPreset: auto"))
        assertNull(fix("- id: other\n  config:\n    defaultPreset: auto"))
        assertNull(fix("not: [yaml"))
    }
}

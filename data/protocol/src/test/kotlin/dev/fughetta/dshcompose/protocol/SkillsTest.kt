package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.skills.SkillInfo
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class SkillsTest {
    @Test
    fun workspaceSkillsKeepTheirSourceDirectory() {
        val source =
            Workspace("source", "/work/project", "Project", persistentSetOf("session"), null)
        val selected = Workspace("selected", "/work/other", "Other", persistentSetOf(), null)
        fun skill(path: String?) = SkillInfo("review", "description", "usage", path, false)
        assertEquals(
            "source",
            skillWorkspaceSwitch(
                skill("/work/project/.dsh/skills/review/SKILL.md"),
                "session",
                listOf(source, selected),
                "selected",
            ),
        )
        assertNull(
            skillWorkspaceSwitch(
                skill("/work/project-2/SKILL.md"),
                "session",
                listOf(source),
                "selected",
            ),
        )
        assertNull(
            skillWorkspaceSwitch(
                skill("/home/user/.dsh/skills/review/SKILL.md"),
                "session",
                listOf(source),
                null,
            ),
        )
        assertNull(
            skillWorkspaceSwitch(
                skill("/work/project/.dsh/skills/review/SKILL.md"),
                "session",
                listOf(source),
                "source",
            ),
        )
    }
}

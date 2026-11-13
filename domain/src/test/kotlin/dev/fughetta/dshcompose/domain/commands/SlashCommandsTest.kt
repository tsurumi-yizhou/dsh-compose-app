package dev.fughetta.dshcompose.domain.commands

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    @Test
    fun catalogPrecedenceLocalizedAliasesAndPartialCommandResolution() {
        val host =
            persistentListOf(
                SlashCommand("compact", "compact", "Compress", null),
                SlashCommand("plan", "plan", "", "objective"),
                SlashCommand("export", "export", "", null),
                SlashCommand("custom", "custom", "", null),
            )
        val app =
            listOf(
                SlashCommand("new", "新对话", "New", null, SlashCommandKind.App),
                SlashCommand("export", "导出", "Zip", null, SlashCommandKind.App),
            )
        val all =
            mergeSlashCommands(
                app,
                host,
                listOf(
                    SlashCommand("plan", "plan", "Collision", "", SlashCommandKind.Skill),
                    SlashCommand("review", "review", "Review", "", SlashCommandKind.Skill),
                ),
            )
        assertEquals(
            listOf("new", "compact", "plan", "export", "custom", "review"),
            all.map { it.name },
        )
        assertEquals(SlashCommandKind.Host, resolveSlashCommand(all, "PLAN")!!.kind)
        assertEquals(SlashCommandKind.App, resolveSlashCommand(all, "导出")!!.kind)
        assertEquals(SlashCommandKind.Skill, resolveSlashCommand(all, "review")!!.kind)
        assertEquals("compact", filterSlashCommands(all, "cpt").first().name)
        assertEquals(
            SlashInput("plan", "\tobjective\nnext"),
            parseSlashInput("  /plan\tobjective\nnext"),
        )
        assertNull(parseSlashInput("text /plan"))
    }
}

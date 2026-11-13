package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.presets.*

fun presetNameResource(id: String): Int? =
    when (id) {
        "standard" -> R.string.preset_standard_name
        "ptc" -> R.string.preset_ptc_name
        "minimal" -> R.string.preset_minimal_name
        "cordis" -> R.string.preset_cordis_name
        else -> null
    }

internal fun presetDescriptionResource(id: String): Int? =
    when (id) {
        "standard" -> R.string.preset_standard_description
        "ptc" -> R.string.preset_ptc_description
        "minimal" -> R.string.preset_minimal_description
        "cordis" -> R.string.preset_cordis_description
        else -> null
    }

internal fun presetRowLabelResource(row: PresetRow): Int? {
    fun module(name: String): Int? =
        when (name) {
            "@deepseek-ai/dsh-agent-instructions" -> R.string.preset_module_instructions
            "@deepseek-ai/dsh-time-context" -> R.string.preset_module_time
            "@deepseek-ai/dsh-tool-bash" -> R.string.preset_module_bash
            "@deepseek-ai/dsh-tool-bash-persistent" -> R.string.preset_module_persistent_bash
            "@deepseek-ai/dsh-tool-pwsh" -> R.string.preset_module_power_shell
            "@deepseek-ai/dsh-tool-fs" -> R.string.preset_module_files
            "@deepseek-ai/dsh-tool-fs-search" -> R.string.preset_module_file_search
            "@deepseek-ai/dsh-tool-jobs" -> R.string.preset_module_jobs
            "@deepseek-ai/dsh-tool-schedule" -> R.string.preset_module_schedule
            "@deepseek-ai/dsh-skill-filesystem" -> R.string.preset_module_local_skills
            "@deepseek-ai/dsh-tool-skill" -> R.string.preset_module_skill_tool
            "@deepseek-ai/dsh-command-goal" -> R.string.preset_module_goal_command
            "@deepseek-ai/dsh-tool-goal" -> R.string.preset_module_goal_tool
            "@deepseek-ai/dsh-plan-mode" -> R.string.preset_module_plan
            "@deepseek-ai/dsh-compaction-basic" -> R.string.preset_module_compaction
            "@deepseek-ai/dsh-command-compact" -> R.string.preset_module_compact_command
            "@deepseek-ai/dsh-tool-ask-user" -> R.string.preset_module_ask_user
            "@deepseek-ai/dsh-tool-todo" -> R.string.preset_module_todo
            "@deepseek-ai/dsh-tool-web" -> R.string.preset_module_web
            "@deepseek-ai/dsh-tool-present" -> R.string.preset_module_present
            "@deepseek-ai/dsh-tool-cordis" -> R.string.preset_module_cordis
            "@deepseek-ai/dsh-tool-subagent" -> R.string.preset_module_subagent
            "@deepseek-ai/dsh-tool-subagent-control" -> R.string.preset_module_subagent_control
            "@deepseek-ai/dsh-tool-workflow" -> R.string.preset_module_workflow
            "@deepseek-ai/dsh-plugin-manager/tools" -> R.string.preset_module_plugin_manager
            "@deepseek-ai/dsh-plugin-market/tools" -> R.string.preset_module_plugin_market
            "@deepseek-ai/dsh-agent-tool-presentation" -> R.string.preset_module_tool_calls
            "@deepseek-ai/dsh-terminal" -> R.string.preset_module_terminal
            "@deepseek-ai/dsh-persona" -> R.string.preset_module_persona
            else -> null
        }
    return if (row.module == "cordis:group")
        when (row.id) {
            "planning" -> R.string.preset_module_plan
            "compaction" -> R.string.preset_module_compaction
            "delegation" -> R.string.preset_group_delegation
            "persistent-shell" -> R.string.preset_group_terminal
            else -> row.children.firstOrNull()?.let(::module)
        }
    else module(row.module)
}

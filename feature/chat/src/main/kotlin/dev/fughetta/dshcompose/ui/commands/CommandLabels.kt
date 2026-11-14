package dev.fughetta.dshcompose.ui.commands

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.AppAction
import dev.fughetta.dshcompose.ui.AppModuleContribution

val builtInCommands =
    listOf(
        AppModuleContribution.Command(
            "new",
            R.string.command_new_label,
            R.string.command_new_description,
        ) {
            AppAction.NewSession
        },
        AppModuleContribution.Command(
            "model",
            R.string.command_model_label,
            R.string.command_model_description,
        ) {
            AppAction.OpenModelPicker(it)
        },
        AppModuleContribution.Command(
            "export",
            R.string.command_export_label,
            R.string.command_export_description,
        ) {
            AppAction.OpenSessionExport(it)
        },
    )

fun nativeCommandCopy(command: SlashCommand, text: (Int) -> String): SlashCommand {
    val face =
        when (command.definitionId) {
            "@deepseek-ai/dsh-command-compact" ->
                Triple(
                    "compact",
                    R.string.slash_state_compact_label,
                    R.string.slash_state_command_compact_description,
                )
            "@deepseek-ai/dsh-permission-presets" ->
                Triple(
                    "permission",
                    R.string.slash_state_permission_label,
                    R.string.slash_state_command_permission_description,
                )
            "@deepseek-ai/dsh-plan-mode" ->
                Triple(
                    "plan",
                    R.string.slash_state_plan_label,
                    R.string.slash_state_command_plan_description,
                )
            "@deepseek-ai/dsh-command-goal" ->
                Triple(
                    "goal",
                    R.string.slash_state_goal_label,
                    R.string.slash_state_command_goal_description,
                )
            "@deepseek-ai/dsh-command-feedback" ->
                Triple(
                    "feedback",
                    R.string.slash_state_feedback_label,
                    R.string.slash_state_command_feedback_description,
                )
            else -> return command
        }
    if (command.name != face.first) return command
    val hint =
        when (command.name) {
            "permission" -> R.string.slash_state_command_permission_hint
            "plan" -> R.string.slash_state_command_plan_hint
            "goal" -> R.string.slash_state_command_goal_hint
            "feedback" -> R.string.slash_state_command_feedback_hint
            else -> null
        }
    return command.copy(
        label = text(face.second),
        description = text(face.third),
        hint = if (!command.takesInput) null else hint?.let(text) ?: command.hint,
    )
}

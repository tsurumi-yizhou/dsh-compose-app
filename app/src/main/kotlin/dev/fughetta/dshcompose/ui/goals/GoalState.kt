package dev.fughetta.dshcompose.ui.goals

import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.goals.*

data class GoalEditorState(
    val ref: GoalRef,
    val initial: String,
    val text: String,
)

data class GoalClearState(val ref: GoalRef, val objective: String)

data class GoalUiState(
    val data: GoalState,
    val dialog: Boolean = false,
    val editor: GoalEditorState? = null,
    val clearing: GoalClearState? = null,
    val dismissedError: String? = null,
) {
    val sessionId
        get() = data.sessionId

    val projection
        get() = data.projection

    val activation
        get() = data.activation

    val canRead
        get() = data.canRead

    val ready
        get() = data.ready

    val busy
        get() = data.busy

    val visible
        get() = data.visible

    val disarmed
        get() = data.disarmed

    val canPause
        get() = data.canPause

    val canResume
        get() = data.canResume

    val error
        get() =
            data.error
                ?.takeUnless { it == dismissedError }
                ?.let {
                    when (it) {
                        "GOAL_READ_FAILED" -> R.string.goal_read_failed
                        "GOAL_STALE_REVISION" -> R.string.goal_stale
                        "GOAL_NOT_FOUND" -> R.string.goal_missing
                        "GOAL_AGENT_NOT_LIVE" -> R.string.goal_not_ready
                        "GOAL_INVALID_OBJECTIVE",
                        "GOAL_INVALID_EDIT" -> R.string.goal_empty
                        "GOAL_INVALID_TRANSITION" -> R.string.goal_state_changed
                        else -> R.string.goal_change_failed
                    }
                }
}

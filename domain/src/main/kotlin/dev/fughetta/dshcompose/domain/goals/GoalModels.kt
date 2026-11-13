package dev.fughetta.dshcompose.domain.goals

enum class GoalPhase {
    Active,
    Paused,
    Blocked,
    Complete,
}

enum class GoalActivation {
    Armed,
    Disarmed,
}

enum class GoalAction {
    Pause,
    Resume,
    Complete,
    Clear,
}

data class GoalRef(val id: String, val revision: Long)

data class GoalBlockReason(val code: String, val message: String)

data class GoalSnapshot(
    val ref: GoalRef,
    val objective: String,
    val phase: GoalPhase,
    val maxGoalRounds: Long,
    val blockedReason: GoalBlockReason?,
)

data class GoalProjection(
    val goal: GoalSnapshot,
    val roundsStarted: Long,
    val createdAt: Long?,
    val updatedAt: Long?,
)

data class GoalView(val projection: GoalProjection, val activation: GoalActivation?) {
    val ref
        get() = projection.goal.ref
}

data class GoalActivationChange(
    val sessionId: String,
    val ref: GoalRef?,
    val activation: GoalActivation?,
)

/** Durable projection and live activation have separate revision clocks. */
data class GoalState(
    val sessionId: String,
    val projection: GoalProjection? = null,
    val activation: GoalActivation? = null,
    val canRead: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val mutationRevision: Long = 0,
    val owner: Long = 0,
) {
    val visible
        get() = projection != null && projection.goal.phase != GoalPhase.Complete

    val disarmed
        get() = projection?.goal?.phase == GoalPhase.Active && activation == GoalActivation.Disarmed

    val canPause
        get() = projection?.goal?.phase == GoalPhase.Active && !disarmed

    val canResume
        get() = projection?.goal?.phase in setOf(GoalPhase.Paused, GoalPhase.Blocked) || disarmed
}

class GoalOperationException(val code: String, cause: Throwable? = null) : Exception(code, cause)

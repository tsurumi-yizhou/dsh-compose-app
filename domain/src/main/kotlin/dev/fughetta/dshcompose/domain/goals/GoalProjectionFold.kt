package dev.fughetta.dshcompose.domain.goals

sealed interface GoalProjectionChange {
    val seq: Long

    data class Replace(override val seq: Long, val projection: GoalProjection?) :
        GoalProjectionChange

    data class Round(override val seq: Long, val goalId: String, val round: Long) :
        GoalProjectionChange
}

/** A snapshot owns every change through [afterSeq], including history outside the loaded window. */
class GoalProjectionFold {
    private var seed: GoalProjection? = null
    var afterSeq = -1L
        private set

    var current: GoalProjection? = null
        private set

    fun seed(projection: GoalProjection?, throughSeq: Long) {
        seed = projection
        afterSeq = throughSeq
    }

    fun apply(change: GoalProjectionChange) {
        if (change.seq > afterSeq) current = fold(current, change)
    }

    fun rebuild(changes: Iterable<GoalProjectionChange>) {
        var result = seed
        for (change in changes) if (change.seq > afterSeq) result = fold(result, change)
        if (result != current) current = result
    }

    private fun fold(current: GoalProjection?, change: GoalProjectionChange): GoalProjection? =
        when (change) {
            is GoalProjectionChange.Replace -> change.projection
            is GoalProjectionChange.Round ->
                if (
                    current != null &&
                        current.goal.ref.id == change.goalId &&
                        change.round > current.roundsStarted
                )
                    current.copy(roundsStarted = change.round)
                else current
        }
}

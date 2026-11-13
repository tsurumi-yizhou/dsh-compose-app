package dev.fughetta.dshcompose.domain.goals

import kotlinx.coroutines.flow.StateFlow

/**
 * Mutations carry GoalState.owner so a retired selection never redirects an action to another host.
 */
interface GoalsRepository {
    val state: StateFlow<GoalState?>

    fun transcript(id: String, projection: GoalProjection?, isRunning: Boolean, ready: Boolean)

    fun observe(sessionId: String, observing: Boolean)

    fun refresh()

    suspend fun edit(owner: Long, ref: GoalRef, objective: String)

    suspend fun change(owner: Long, ref: GoalRef, action: GoalAction)

    suspend fun clear(owner: Long, ref: GoalRef)
}

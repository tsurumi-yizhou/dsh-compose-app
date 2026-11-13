package dev.fughetta.dshcompose.domain.schedules

import kotlinx.coroutines.flow.Flow

/** Schedule inventory and mutations owned by one connection generation. */
interface SchedulesRepository {
    val active: Boolean
    val canManage: Boolean
    val changes: Flow<Unit>

    fun invalidate()

    suspend fun list(active: Boolean?, offset: Int): SchedulePage

    suspend fun catalog(): List<ScheduleTask>

    suspend fun history(id: String, before: String?): ScheduleHistory

    suspend fun create(input: ScheduleInput): ScheduleTask

    suspend fun update(task: ScheduleTask, changes: ScheduleChanges): ScheduleTask

    suspend fun delete(task: ScheduleTask)

    suspend fun run(task: ScheduleTask)
}

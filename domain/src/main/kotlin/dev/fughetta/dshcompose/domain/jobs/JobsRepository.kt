package dev.fughetta.dshcompose.domain.jobs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Job subscriptions and acknowledged stop requests owned by one connection generation. */
class JobOutputExportException(val code: String, cause: Throwable? = null) : Exception(code, cause)

interface JobsRepository {
    val connection: StateFlow<JobsRepository?>

    suspend fun share(lines: List<String>): String

    suspend fun save(destination: String, lines: List<String>)

    val active: Boolean
    val canStop: Boolean

    fun watch(sessionId: String): Flow<JobRoster>

    fun output(sessionId: String, job: JobInfo): Flow<JobOutput>

    suspend fun stop(sessionId: String, jobId: String): Boolean
}

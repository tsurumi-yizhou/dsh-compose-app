package dev.fughetta.dshcompose.ui.jobs

import dev.fughetta.dshcompose.domain.jobs.*
import kotlinx.collections.immutable.*

data class JobsUiState(
    val sessionId: String,
    val jobs: PersistentList<JobInfo> = persistentListOf(),
    val loading: Boolean = true,
    val output: JobOutputUiState? = null,
    val canStop: Boolean = false,
    val error: String? = null,
    val export: JobExportState = JobExportState(),
)

data class JobOutputUiState(
    val data: JobOutput,
    val stopRequested: Boolean = false,
    val stopping: Boolean = false,
    val confirmStop: Boolean = false,
    val notice: Int? = null,
    val error: String? = null,
) {
    val job
        get() = data.job

    val lines = data.lines.toPersistentList()

    val firstLine
        get() = data.firstLine

    val trimmed
        get() = data.trimmed

    val loading
        get() = data.loading
}

data class JobShareRequest(val id: Long, val file: java.io.File)

data class JobSaveRequest(val id: Long, val name: String = "task-output.txt")

data class JobExportState(
    val sharing: Boolean = false,
    val shareFailed: Boolean = false,
    val shareRequest: JobShareRequest? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val saveRequest: JobSaveRequest? = null,
)

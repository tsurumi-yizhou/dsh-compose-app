package dev.fughetta.dshcompose.domain.jobs

data class JobInfo(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val progress: String?,
    val detail: String?,
    val startedAt: Long?,
    val finishedAt: Long?,
    val outputBytes: Long,
) {
    val live
        get() = status == "running" || status == "stopping"
}

data class JobRoster(val jobs: List<JobInfo> = emptyList(), val loading: Boolean = true)

data class JobOutput(
    val job: JobInfo,
    val lines: List<String> = emptyList(),
    val firstLine: Long = 0,
    val trimmed: Boolean = false,
    val loading: Boolean = true,
)

class JobOperationException(val code: String, cause: Throwable? = null) : Exception(code, cause)

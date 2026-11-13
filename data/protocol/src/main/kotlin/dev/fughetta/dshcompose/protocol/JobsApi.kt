package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.jobs.JobInfo
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

sealed interface JobsUpdate {
    data object Reset : JobsUpdate

    data class Snapshot(val jobs: PersistentList<JobInfo>) : JobsUpdate
}

sealed interface JobFrame {
    data object Reset : JobFrame

    data class Opened(val job: JobInfo) : JobFrame

    data class Output(val chunks: List<JobChunk>, val lossy: Boolean) : JobFrame

    data class Status(val job: JobInfo) : JobFrame
}

data class JobChunk(val text: String, val channel: String?, val gapBefore: Boolean)

private fun JsonElement.job(): JobInfo {
    val value = objectValue()
    val id = value.requiredString("id")
    return JobInfo(
        id,
        value.string("kind") ?: "bash",
        value.string("label") ?: id,
        value.string("status") ?: "running",
        value.string("progress"),
        value.string("detail"),
        value.long("startedAt"),
        value.long("finishedAt"),
        (value["output"] as? JsonObject)?.long("total") ?: 0,
    )
}

fun ProtocolClient.watchJobs(sessionId: String): Flow<JobsUpdate> = flow {
    mux.stream(
            "job/list",
            buildJsonObject { putJsonObject("request") { put("sessionId", sessionId) } },
        )
        .collect { event ->
            when (event) {
                is MuxEvent.Reset -> emit(JobsUpdate.Reset)
                is MuxEvent.Item -> {
                    val jobs =
                        event.value.objectValue()["jobs"] as? JsonArray
                            ?: throw HostProtocolException("Missing jobs")
                    emit(
                        JobsUpdate.Snapshot(
                            jobs
                                .map { it.job() }
                                .distinctBy { it.id }
                                .sortedWith(
                                    compareByDescending<JobInfo> { it.live }
                                        .thenByDescending { it.startedAt ?: 0 },
                                )
                                .toPersistentList(),
                        ),
                    )
                }
            }
        }
}
    .flowOn(Dispatchers.Default)

fun ProtocolClient.followJob(sessionId: String, jobId: String): Flow<JobFrame> = flow {
    var opened = false
    mux.stream(
            "job/follow",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", sessionId)
                    put("jobId", jobId)
                }
            },
        )
        .collect { event ->
            when (event) {
                is MuxEvent.Reset -> {
                    opened = false
                    emit(JobFrame.Reset)
                }
                is MuxEvent.Item -> {
                    val value = event.value.objectValue()
                    val frame =
                        when (value.string("type")) {
                            "opened" ->
                                JobFrame.Opened(
                                        (value["job"] ?: throw HostProtocolException("Missing job"))
                                            .job(),
                                    )
                                    .also {
                                        if (it.job.id != jobId)
                                            throw HostProtocolException("Unexpected job")
                                        opened = true
                                    }
                            "output" ->
                                JobFrame.Output(
                                    (value["chunks"] as? JsonArray).orEmpty().map { raw ->
                                        val chunk = raw.objectValue()
                                        JobChunk(
                                            chunk.string("text").orEmpty(),
                                            chunk.string("channel"),
                                            chunk.boolean("gapBefore") == true,
                                        )
                                    },
                                    value.boolean("lossy") == true,
                                )
                            "status" ->
                                JobFrame.Status(
                                        (value["job"] ?: throw HostProtocolException("Missing job"))
                                            .job(),
                                    )
                                    .also {
                                        if (it.job.id != jobId)
                                            throw HostProtocolException("Unexpected job")
                                    }
                            else -> null
                        }
                    if (frame != null) {
                        if (!opened) throw HostProtocolException("Missing job opened frame")
                        emit(frame)
                    }
                }
            }
        }
}
    .flowOn(Dispatchers.Default)

suspend fun ProtocolClient.killJob(sessionId: String, jobId: String): Boolean {
    if (!connection.supportsJobStop) throw HostRemoteException("JOB_STOP_UNSUPPORTED")
    val value =
        connection
            .call(
                "job/kill",
                buildJsonObject {
                    putJsonObject("request") {
                        put("sessionId", sessionId)
                        put("jobId", jobId)
                    }
                },
            )
            .objectValue()
    return value.requiredString("outcome") == "requested"
}

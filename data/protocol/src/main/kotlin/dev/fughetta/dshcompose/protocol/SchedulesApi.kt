package dev.fughetta.dshcompose.protocol

import java.time.Instant
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Current Host task vocabulary; delivery destinations never imply a locally created Session. */
data class ScheduleTask(
    val id: String,
    val title: String,
    val prompt: String,
    val kind: String,
    val status: String,
    val target: JsonObject,
    val involvedSessionIds: PersistentList<String>,
    val updatedAt: String?,
    val scheduledAt: Instant?,
    val lastDeliveredAt: Instant?,
    val raw: JsonObject,
) {
    val active
        get() = status == "active"

    val targetSessionId
        get() = target.string("sessionId")

    companion object {
        internal fun parse(value: JsonElement): ScheduleTask {
            val row = value.objectValue()
            val target =
                row["target"]?.objectValue()
                    ?: throw HostProtocolException("Missing schedule destination")
            if (target.string("kind") !in setOf("session", "new_once", "new"))
                throw HostProtocolException("Invalid schedule destination")
            return ScheduleTask(
                row.requiredString("id"),
                row.requiredString("title"),
                row.string("prompt").orEmpty(),
                row.requiredString("kind"),
                row.requiredString("status"),
                target,
                (row["involvedSessionIds"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .toPersistentList(),
                row.string("updatedAt"),
                row.instant("scheduledAt"),
                (row["lastDelivery"] as? JsonObject)?.instant("deliveredAt"),
                row,
            )
        }
    }
}

private fun JsonObject.instant(key: String): Instant? =
    string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

data class SchedulePage(
    val items: PersistentList<ScheduleTask>,
    val total: Int,
    val offset: Int,
    val shown: Int,
)

data class ScheduleRun(
    val messageId: String?,
    val scheduledAt: Instant?,
    val deliveredAt: Instant?,
    val prompt: String?,
    val targetSessionId: String?,
)

data class ScheduleHistory(
    val runs: PersistentList<ScheduleRun>,
    val nextBefore: String?,
    val pruned: Boolean,
    val unavailable: Boolean,
    val retentionDays: Long?,
    val retentionRecords: Long?,
)

suspend fun ProtocolClient.listSchedules(
    active: Boolean? = null,
    offset: Int = 0,
    limit: Int = 100,
): SchedulePage =
    withContext(Dispatchers.Default) {
        require(offset >= 0 && limit in 1..500)
        val result =
            connection
                .call(
                    "schedule/list",
                    buildJsonObject {
                        putJsonObject("request") {
                            active?.let { put("active", it) }
                            put("offset", offset)
                            put("limit", limit)
                        }
                    },
                )
                .objectValue()
        SchedulePage(
            (result["items"] as? JsonArray ?: throw HostProtocolException("Missing schedule page"))
                .map(ScheduleTask::parse)
                .toPersistentList(),
            result.requiredLong("total").toInt(),
            result.requiredLong("offset").toInt(),
            result.requiredLong("shown").toInt(),
        )
    }

suspend fun ProtocolClient.scheduleCatalog(): PersistentList<ScheduleTask> =
    withContext(Dispatchers.Default) {
        (connection.call("schedule/catalog") as? JsonArray
                ?: throw HostProtocolException("Missing schedule catalog"))
            .map(ScheduleTask::parse)
            .toPersistentList()
    }

suspend fun ProtocolClient.scheduleHistory(
    id: String,
    before: String? = null,
    limit: Int = 20,
): ScheduleHistory =
    withContext(Dispatchers.Default) {
        require(limit in 1..100)
        val result =
            connection
                .call(
                    "schedule/history",
                    buildJsonObject {
                        putJsonObject("request") {
                            put("id", id)
                            put("limit", limit)
                            before?.let { put("before", it) }
                        }
                    },
                )
                .objectValue()
        result.string("code")?.let { throw HostRemoteException(it) }
        val retention = result["retention"] as? JsonObject
        ScheduleHistory(
            (result["records"] as? JsonArray)
                .orEmpty()
                .map { raw ->
                    raw.objectValue().let {
                        ScheduleRun(
                            it.string("messageId"),
                            it.instant("scheduledAt"),
                            it.instant("deliveredAt"),
                            it.string("prompt"),
                            it.string("targetSessionId"),
                        )
                    }
                }
                .toPersistentList(),
            result.string("nextBefore"),
            result.boolean("earlierRecordsPruned") == true,
            result.boolean("earlierRecordsUnavailable") == true,
            retention?.long("days"),
            retention?.long("records"),
        )
    }

private fun ProtocolClient.requireScheduleManagement() {
    if (!connection.supportsScheduleManagement)
        throw HostRemoteException("SCHEDULE_MANAGEMENT_UNSUPPORTED")
}

private fun scheduleResult(result: JsonElement): JsonObject =
    result.objectValue().also {
        it.string("code")?.let { code ->
            throw HostRemoteException(code, explanation = it.string("message"))
        }
    }

suspend fun ProtocolClient.createSchedule(
    title: String,
    prompt: String,
    timing: JsonObject,
    target: JsonObject,
): ScheduleTask {
    requireScheduleManagement()
    val result =
        scheduleResult(
            connection.call(
                "schedule/create",
                buildJsonObject {
                    putJsonObject("request") {
                        put("title", title.trim())
                        put("prompt", prompt.trim())
                        put("timing", timing)
                        put("target", target)
                    }
                },
            ),
        )
    return ScheduleTask.parse(result)
}

suspend fun ProtocolClient.editSchedule(
    id: String,
    expectedUpdatedAt: String?,
    patch: JsonObject,
): ScheduleTask {
    requireScheduleManagement()
    require(patch.isNotEmpty())
    val result =
        scheduleResult(
            connection.call(
                "schedule/edit",
                buildJsonObject {
                    putJsonObject("request") {
                        put("id", id)
                        put("action", "update")
                        put(
                            "expectedUpdatedAt",
                            expectedUpdatedAt?.let(::JsonPrimitive) ?: JsonNull,
                        )
                        put("patch", patch)
                    }
                },
            ),
        )
    val changed = ScheduleTask.parse(result)
    if (changed.id != id) throw HostProtocolException("Unexpected edited task")
    return changed
}

suspend fun ProtocolClient.deleteSchedule(id: String, expectedUpdatedAt: String?): Boolean {
    requireScheduleManagement()
    val result =
        connection
            .call(
                "schedule/edit",
                buildJsonObject {
                    putJsonObject("request") {
                        put("id", id)
                        put("action", "delete")
                        put(
                            "expectedUpdatedAt",
                            expectedUpdatedAt?.let(::JsonPrimitive) ?: JsonNull,
                        )
                    }
                },
            )
            .objectValue()
    if (result.string("code") == "schedule_not_found") return false
    result.string("code")?.let { throw HostRemoteException(it) }
    return result.boolean("deleted")
        ?: throw HostProtocolException("Missing schedule deletion result")
}

suspend fun ProtocolClient.runSchedule(id: String) {
    requireScheduleManagement()
    scheduleResult(
        connection.call(
            "schedule/run",
            buildJsonObject { putJsonObject("request") { put("id", id) } },
        ),
    )
}

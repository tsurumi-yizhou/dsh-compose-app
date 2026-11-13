package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*

data class SessionRecord(val seq: Long, val time: Long, val type: String, val data: JsonObject) {
    companion object {
        internal fun fromJson(value: JsonElement): SessionRecord {
            val obj = value.objectValue()
            return SessionRecord(
                obj.requiredLong("seq"),
                obj.requiredLong("time"),
                obj.requiredString("type"),
                obj["data"]?.objectValue() ?: JsonObject(emptyMap()),
            )
        }
    }
}

data class HistoryPage(val records: List<SessionRecord>, val hasMore: Boolean)

sealed interface SessionUpdate {
    /** Controls must wait for Snapshot before using the new generation's state. */
    data class Reset(val generation: ConnectionGeneration) : SessionUpdate

    data class Snapshot(
        val generation: ConnectionGeneration,
        val cursor: Long,
        val records: List<SessionRecord>,
        val hasMore: Boolean,
        val header: JsonObject,
        val projections: JsonObject,
        val assistantStream: JsonObject?,
        val historyReplaced: Boolean,
    ) : SessionUpdate

    data class Record(val event: SessionRecord) : SessionUpdate

    data class Assistant(val frame: JsonObject) : SessionUpdate

    data class Other(val frame: JsonObject) : SessionUpdate
}

private class Resync : Exception()

private class RetiredFollow : Exception()

/**
 * Snapshot first, contiguous durable events thereafter; restores missed history after socket loss.
 */
fun ProtocolClient.watchSession(sessionId: String): Flow<SessionUpdate> = flow {
    require(sessionId.isNotEmpty())
    var lastSeq = -1L
    var failures = 0
    while (currentCoroutineContext().isActive) {
        var generation: ConnectionGeneration? = null
        var awaitingSnapshot = true
        var assistantRevision = 0L
        try {
            mux.stream(
                    "session/follow",
                    buildJsonObject {
                        putJsonObject("request") {
                            put("address", sessionAddress(sessionId))
                            put("assistantStream", true)
                            put("maxMessages", 100)
                            putJsonObject("turnWindow") {
                                put("minMessages", 30)
                                put("minTurns", 2)
                            }
                        }
                    },
                )
                .collect { update ->
                    when (update) {
                        is MuxEvent.Reset -> {
                            generation = update.generation
                            awaitingSnapshot = true
                            emit(SessionUpdate.Reset(update.generation))
                        }
                        is MuxEvent.Item -> {
                            val owner = generation ?: throw Resync()
                            val frame = update.value.objectValue()
                            if (awaitingSnapshot) {
                                if (frame.string("type") != "snapshot") throw Resync()
                                val cursor = frame.requiredLong("cursor")
                                val window = frame.records()
                                if (window.isNotEmpty() && window.last().seq != cursor)
                                    throw Resync()
                                ensureContiguous(window)
                                val firstSeq = window.firstOrNull()?.seq ?: cursor + 1
                                val repaired =
                                    if (lastSeq >= 0 && firstSeq > lastSeq + 1) {
                                        fillHistory(sessionId, lastSeq + 1, firstSeq, cursor, owner)
                                    } else emptyList()
                                if (mux.state.value.generation != owner) throw RetiredFollow()
                                val assistant = frame["assistantStream"] as? JsonObject
                                assistantRevision = assistant?.long("revision") ?: 0
                                emit(
                                    SessionUpdate.Snapshot(
                                        owner,
                                        cursor,
                                        repaired + window,
                                        frame.boolean("hasMore") == true,
                                        frame["header"]?.objectValue() ?: throw Resync(),
                                        frame["projections"]?.objectValue() ?: throw Resync(),
                                        assistant,
                                        cursor < lastSeq,
                                    ),
                                )
                                lastSeq = cursor
                                awaitingSnapshot = false
                            } else
                                when (frame.string("type")) {
                                    "event" -> {
                                        val record =
                                            SessionRecord.fromJson(frame["event"] ?: throw Resync())
                                        if (record.seq > lastSeq) {
                                            if (record.seq != lastSeq + 1) throw Resync()
                                            lastSeq = record.seq
                                            emit(SessionUpdate.Record(record))
                                            failures = 0
                                        }
                                    }
                                    "assistant-stream" -> {
                                        val assistant =
                                            frame["frame"]?.objectValue() ?: throw Resync()
                                        val revision = assistant.requiredLong("revision")
                                        if (revision != assistantRevision + 1) throw Resync()
                                        assistantRevision = revision
                                        emit(SessionUpdate.Assistant(assistant))
                                        failures = 0
                                    }
                                    "snapshot" -> throw Resync()
                                    else -> emit(SessionUpdate.Other(frame))
                                }
                        }
                    }
                }
            // A Host may end a live follow normally. Reopen it rather than leaving a frozen view.
            delay(500)
        } catch (_: RetiredFollow) {
            // A history read finished after its owning generation was retired. Discard it.
        } catch (_: Resync) {
            if (++failures >= 6)
                throw HostProtocolException("Could not restore a contiguous session follow")
            delay((250L shl (failures - 1)).coerceAtMost(8000))
        }
    }
}

private suspend fun ProtocolClient.fillHistory(
    sessionId: String,
    from: Long,
    before: Long,
    through: Long,
    owner: ConnectionGeneration,
): List<SessionRecord> {
    var edge = before
    val pages = mutableListOf<List<SessionRecord>>()
    while (edge > from) {
        if (mux.state.value.generation != owner) throw RetiredFollow()
        val page = history(sessionId, through, edge)
        if (mux.state.value.generation != owner) throw RetiredFollow()
        val records = page.records.filter { it.seq >= from && it.seq < edge }
        if (records.isEmpty()) throw Resync()
        ensureContiguous(records)
        if (records.last().seq != edge - 1) throw Resync()
        pages += records
        edge = records.first().seq
    }
    val result = pages.asReversed().flatten()
    if (result.firstOrNull()?.seq != from || result.lastOrNull()?.seq != before - 1) throw Resync()
    return result
}

internal fun JsonObject.records(): List<SessionRecord> =
    (get("records") as? JsonArray ?: throw HostProtocolException("Missing history records")).map {
        SessionRecord.fromJson(
            it.objectValue()["event"] ?: throw HostProtocolException("Missing history event"),
        )
    }

private fun ensureContiguous(records: List<SessionRecord>) {
    if (records.zipWithNext().any { (a, b) -> b.seq != a.seq + 1 }) throw Resync()
}

fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

fun JsonObject.requiredLong(key: String): Long =
    long(key) ?: throw HostProtocolException("Missing $key")

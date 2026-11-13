package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

fun decodeSessionSummary(value: JsonElement): SessionSummary {
    val obj = value.objectValue()
    val projections = obj["projections"] as? JsonObject ?: JsonObject(emptyMap())
    return SessionSummary(
        obj.requiredString("sessionId"),
        (projections["values"] as? JsonObject)?.string("title"),
        obj.string("cwd"),
        obj.boolean("running") == true,
        obj.boolean("blank") == true,
        obj.boolean("agentAvailable") == true,
        obj.string("parentSessionId"),
        obj.string("origin"),
        (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0,
        decodeSessionSubagents(projections),
    )
}

fun sessionSummaryFromSnapshot(
    sessionId: String,
    snapshot: SessionUpdate.Snapshot,
    running: Boolean,
): SessionSummary {
    val values = snapshot.projections["values"] as? JsonObject
    val metadata = values?.get("sessionListMetadata") as? JsonObject
    return SessionSummary(
        sessionId,
        values?.string("title"),
        snapshot.header.string("cwd"),
        running,
        metadata?.boolean("blank") == true,
        false,
        snapshot.header.string("parentSessionId"),
        snapshot.header.string("origin"),
        snapshot.records.lastOrNull()?.time ?: snapshot.header.long("createdAt") ?: 0,
        decodeSessionSubagents(snapshot.projections),
    )
}

fun decodeSessionSubagents(projections: JsonObject): SessionSubagents {
    val values = projections["values"] as? JsonObject
    val catalog =
        (values?.get("subagentCatalog") as? JsonArray)
            .orEmpty()
            .mapNotNull { raw ->
                val entry = raw as? JsonObject ?: return@mapNotNull null
                SubagentCatalogEntry(
                    entry.string("id") ?: return@mapNotNull null,
                    entry.string("label"),
                    entry.string("mode"),
                    entry.long("createdAt"),
                )
            }
            .toPersistentList()
    val identity =
        (values?.get("subagent") as? JsonObject)?.let {
            SubagentIdentity(it.string("label"), it.string("mode"))
        }
    val timing =
        (values?.get("subagentTiming") as? JsonObject)?.let {
            val active = it["active"] as? JsonObject
            SubagentTiming(
                it.long("settledMs") ?: 0,
                active?.long("since"),
                active?.long("through"),
                it.boolean("lastTurnCompleted"),
            )
        }
    return SessionSubagents(catalog, identity, timing)
}

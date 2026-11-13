package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.serialization.json.*

fun parseAgentTeamSnapshot(value: JsonElement?): AgentTeamSnapshot? {
    val o = value as? JsonObject ?: return null
    fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    fun JsonObject.strings(k: String) =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val members =
        (o["members"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            AgentTeamMember(
                row.text("id") ?: return@mapNotNull null,
                row.text("name").orEmpty(),
                row.text("role") == "lead",
                row.text("phase") ?: "active",
                row.text("error"),
            )
        }
    val tasks =
        (o["tasks"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            if (row.text("status") == "deleted") return@mapNotNull null
            AgentTeamTask(
                row.text("id") ?: return@mapNotNull null,
                row.text("subject").orEmpty(),
                row.text("description").orEmpty(),
                row.text("status") ?: "pending",
                row.text("ownerName"),
                (row["ready"] as? JsonPrimitive)?.booleanOrNull == true,
                row.strings("blockedBy"),
                row.strings("writeScopes"),
                row.strings("writeScopeWarnings"),
            )
        }
    return AgentTeamSnapshot(members, tasks, o.text("failure"))
}

suspend fun ProtocolClient.agentTeamMessages(sessionId: String): List<AgentTeamMessage> {
    val seq =
        (projections(sessionId)?.get("asOfSeq") as? JsonPrimitive)?.longOrNull ?: return emptyList()
    val answer =
        connection.call(
            "session/page",
            buildJsonObject {
                putJsonObject("request") {
                    put("address", sessionAddress(sessionId))
                    put("throughSeq", seq)
                    put("maxMessages", 200)
                }
            },
        ) as? JsonObject ?: throw HostProtocolException("Missing team message page")
    return (answer["records"] as? JsonArray).orEmpty().mapNotNull { record ->
        val row = record as? JsonObject ?: return@mapNotNull null
        if ((row["type"] as? JsonPrimitive)?.contentOrNull != "user/message") return@mapNotNull null
        val data = row["data"] as? JsonObject ?: return@mapNotNull null
        val source = data["source"] as? JsonObject ?: return@mapNotNull null
        fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        val kind = source.text("kind")
        if (kind !in setOf("agent-message", "team-message")) return@mapNotNull null
        val parts =
            (data["content"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.text("type") == "text" }
                .map { it.text("text").orEmpty() }
                .toMutableList()
        var name = source.text("senderName")
        val first = parts.firstOrNull()?.trim()
        if (first?.startsWith("Team message from ") == true && first.endsWith(':')) {
            name = name ?: first.removePrefix("Team message from ").dropLast(1)
            parts.removeAt(0)
        } else if (kind == "agent-message" && name == null) return@mapNotNull null
        val text =
            parts.joinToString("\n").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        AgentTeamMessage(
            sessionId,
            source.text("senderSessionId") ?: source.text("senderId"),
            name,
            text,
            (row["time"] as? JsonPrimitive)?.longOrNull ?: 0,
            (row["seq"] as? JsonPrimitive)?.longOrNull ?: 0,
        )
    }
}

package dev.fughetta.dshcompose.protocol

import kotlinx.serialization.json.*

data class BotProfile(
    val id: String,
    val name: String,
    val role: String,
    val instructions: String,
    val color: String?,
    val sessionId: String,
    val parts: List<String>,
    val model: String?,
    val appliedModel: String?,
    val effort: String?,
    val workDir: String?,
    val waiting: Boolean,
    val imageRef: String?,
) {
    companion object {
        fun parse(o: JsonObject) =
            BotProfile(
                o.botText("id").orEmpty(),
                o.botText("name").orEmpty(),
                o.botText("role").orEmpty(),
                o.botText("instructions").orEmpty(),
                o.botText("color"),
                o.botText("sessionId") ?: o.botText("id").orEmpty(),
                o.botStrings("parts"),
                o.botText("model"),
                o.botText("appliedModel"),
                o.botText("effort"),
                o.botText("workDir"),
                o.botBool("waiting"),
                (o["avatar"] as? JsonObject)?.botText("imageRef"),
            )
    }
}

data class BotRoom(
    val id: String,
    val name: String,
    val members: List<String>,
    val admin: String?,
    val mode: String,
    val notice: String,
    val workDir: String?,
    val parts: List<String>,
    val relayLimit: Int?,
    val relayDefault: Int?,
    val paused: List<String>,
) {
    companion object {
        fun parse(o: JsonObject) =
            BotRoom(
                o.botText("id").orEmpty(),
                o.botText("name").orEmpty(),
                o.botStrings("members"),
                o.botText("admin"),
                o.botText("mode") ?: "admin",
                o.botText("notice").orEmpty(),
                o.botText("workDir"),
                o.botStrings("parts"),
                o.botLong("relayLimit")?.toInt(),
                o.botLong("relayLimitDefault")?.toInt(),
                (o["paused"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.botText("id")
                },
            )
    }
}

data class BotChatItem(
    val id: String?,
    val kind: String,
    val text: String,
    val time: Long?,
    val botId: String?,
    val name: String?,
    val event: String?,
    val role: String?,
    val live: Boolean,
    val images: Int,
    val whole: JsonObject?,
    val raw: JsonObject,
) {
    companion object {
        fun parse(o: JsonObject) =
            BotChatItem(
                o.botText("id"),
                o.botText("kind").orEmpty(),
                o.botText("text").orEmpty(),
                o.botLong("time"),
                o.botText("botId"),
                o.botText("name"),
                o.botText("event"),
                o.botText("role"),
                o.botBool("live"),
                o.botLong("images")?.toInt() ?: 0,
                o["whole"] as? JsonObject,
                o,
            )
    }

    fun same(other: BotChatItem) =
        if (id != null && other.id != null) id == other.id
        else kind == other.kind && time == other.time && text == other.text
}

data class BotApproval(
    val id: String,
    val sessionId: String,
    val botId: String,
    val roomId: String?,
    val tool: String,
    val reason: String,
    val answerable: Boolean,
) {
    companion object {
        fun parse(o: JsonObject) =
            BotApproval(
                o.botText("id").orEmpty(),
                o.botText("sessionId").orEmpty(),
                o.botText("botId").orEmpty(),
                o.botText("roomId"),
                o.botText("tool").orEmpty(),
                o.botText("reason").orEmpty(),
                o.botBool("answerable"),
            )
    }
}

data class BotNotice(
    val seq: Long,
    val kind: String,
    val botId: String,
    val roomId: String?,
    val name: String,
    val title: String?,
    val text: String,
    val time: Long?,
) {
    val conversationId
        get() = roomId ?: botId

    companion object {
        fun parse(o: JsonObject) =
            BotNotice(
                o.botLong("seq") ?: 0,
                o.botText("kind").orEmpty(),
                o.botText("botId").orEmpty(),
                o.botText("roomId"),
                o.botText("name").orEmpty(),
                o.botText("title"),
                o.botText("text").orEmpty(),
                o.botLong("time"),
            )
    }
}

data class BotTeam(
    val revision: Long,
    val bots: List<BotProfile>,
    val rooms: List<BotRoom>,
    val mainIds: Set<String>,
    val questions: JsonObject,
    val views: JsonObject,
    val models: List<JsonObject>,
    val defaultModel: String?,
    val modelsKey: String?,
    val memory: Boolean,
    val readOnly: String?,
) {
    val ownedSessions: Set<String>
        get() = buildSet {
            bots.forEach {
                add(it.id)
                add(it.sessionId)
                addAll(it.parts)
            }
            rooms.forEach {
                add(it.id)
                addAll(it.parts)
            }
        }

    companion object {
        fun parse(o: JsonObject) =
            BotTeam(
                o.botLong("revision") ?: 0,
                (o["bots"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map(BotProfile::parse),
                (o["rooms"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map(BotRoom::parse),
                o.botStrings("mainBotIds").toSet(),
                o["questions"] as? JsonObject ?: JsonObject(emptyMap()),
                o["views"] as? JsonObject ?: JsonObject(emptyMap()),
                (o["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject },
                o.botText("defaultModel"),
                o.botText("modelsKey"),
                o["memory"] != JsonPrimitive(false),
                o.botText("readOnly"),
            )
    }
}

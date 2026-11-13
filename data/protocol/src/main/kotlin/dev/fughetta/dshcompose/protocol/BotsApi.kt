package dev.fughetta.dshcompose.protocol

import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Bot commands and subscriptions use the compose application protocol. */
class BotsApi(val client: ProtocolClient) {
    suspend fun call(endpoint: String, payload: JsonObject = JsonObject(emptyMap())): JsonElement {
        val answer =
            client.connection.call(
                "dsBot/call",
                buildJsonObject {
                    put("endpoint", endpoint)
                    put("payload", payload)
                },
            ) as? JsonObject ?: throw HostProtocolException("Invalid bot response")
        if ((answer["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val error = answer["error"] as? JsonObject
            throw HostRemoteException(
                (error?.get("code") as? JsonPrimitive)?.contentOrNull ?: "bot/request-failed",
            )
        }
        return answer["value"] ?: JsonNull
    }

    suspend fun hello(): JsonObject =
        call(
                "hello",
                buildJsonObject {
                    putJsonObject("client") {
                        put("name", "dsh-compose")
                        put("platform", "android")
                    }
                    putJsonArray("api") { add(1) }
                },
            )
            .botObject()

    suspend fun state(since: Long? = null, models: String? = null, locale: String): JsonObject =
        call(
                "state",
                buildJsonObject {
                    since?.let { put("since", it) }
                    models?.let { put("models", it) }
                    put("locale", locale)
                    put("avatars", "ref")
                },
            )
            .botObject()

    suspend fun chat(id: String, room: Boolean, cursor: JsonElement? = null): JsonObject =
        call(
                if (room) "room-chat" else "chat",
                buildJsonObject {
                    put(if (room) "roomId" else "botId", id)
                    cursor?.takeIf { it != JsonNull }?.let { put("cursor", it) }
                },
            )
            .botObject()

    suspend fun send(id: String, text: String) {
        call(
            "send",
            buildJsonObject {
                put("sessionId", id)
                put("text", text)
            },
        )
    }

    suspend fun createBot(fields: JsonObject): BotProfile =
        BotProfile.parse(call("create-bot", fields).botObject())

    suspend fun updateBot(id: String, fields: JsonObject) {
        call("update-bot", JsonObject(fields + ("id" to JsonPrimitive(id))))
    }

    suspend fun duplicateBot(id: String): BotProfile =
        BotProfile.parse(call("duplicate-bot", buildJsonObject { put("id", id) }).botObject())

    suspend fun deleteBot(id: String) {
        call("delete-bot", buildJsonObject { put("id", id) })
    }

    suspend fun setMain(id: String, main: Boolean) {
        call(
            "set-main",
            buildJsonObject {
                put("id", id)
                put("main", main)
            },
        )
    }

    suspend fun createRoom(fields: JsonObject): BotRoom =
        BotRoom.parse(call("create-room", fields).botObject())

    suspend fun updateRoom(id: String, fields: JsonObject) {
        call("update-room", JsonObject(fields + ("id" to JsonPrimitive(id))))
    }

    suspend fun deleteRoom(id: String) {
        call("delete-room", buildJsonObject { put("id", id) })
    }

    suspend fun continueRoom(id: String) {
        call("room-continue", buildJsonObject { put("roomId", id) })
    }

    suspend fun stopMember(room: String, bot: String): Boolean =
        (call(
                "stop-member-work",
                buildJsonObject {
                    put("roomId", room)
                    put("botId", bot)
                },
            )
                as? JsonPrimitive)
            ?.booleanOrNull ?: throw HostProtocolException("Invalid bot stop result")

    suspend fun answerApproval(id: String, allow: Boolean) {
        call(
            "approval-answer",
            buildJsonObject {
                put("id", id)
                put("choice", if (allow) "allow" else "reject")
            },
        )
    }

    suspend fun answerQuestion(
        session: String,
        id: String,
        selected: List<String>,
        custom: String?,
    ) {
        call(
            "answer-question",
            buildJsonObject {
                put("sessionId", session)
                put("questionId", id)
                putJsonObject("answer") {
                    put("selected", JsonArray(selected.map(::JsonPrimitive)))
                    custom?.let { put("custom", it) }
                }
            },
        )
    }

    suspend fun dismissQuestion(session: String) {
        call("dismiss-question", buildJsonObject { put("sessionId", session) })
    }

    suspend fun answerView(session: String, id: String, action: String, values: JsonObject) {
        call(
            "view-reply",
            buildJsonObject {
                put("sessionId", session)
                put("viewId", id)
                put("action", action)
                put("values", values)
            },
        )
    }

    suspend fun dismissView(session: String) {
        call("dismiss-view", buildJsonObject { put("sessionId", session) })
    }

    suspend fun memory(bot: String?, room: String?): JsonObject =
        call("memory-view", target(bot, room)).botObject()

    suspend fun mutateMemory(
        endpoint: String,
        bot: String?,
        room: String?,
        fields: JsonObject,
    ): JsonObject = call(endpoint, JsonObject(target(bot, room) + fields)).botObject()

    suspend fun usage(id: String): JsonObject =
        call(
                "usage-summary",
                buildJsonObject {
                    put("botId", id)
                    put("timeZone", java.util.TimeZone.getDefault().id)
                },
            )
            .botObject()

    suspend fun folders(): JsonObject = call("folders").botObject()

    suspend fun avatar(id: String): JsonObject =
        call("avatar", buildJsonObject { put("botId", id) }).botObject()

    suspend fun fileText(file: JsonObject): JsonObject =
        call("file-text", buildJsonObject { put("file", file) }).botObject()

    suspend fun notices(boot: String?, since: Long?): JsonObject =
        call(
                "notices",
                buildJsonObject {
                    boot?.let { put("boot", it) }
                    since?.let { put("since", it) }
                },
            )
            .botObject()

    private fun target(bot: String?, room: String?) = buildJsonObject {
        if (room != null) put("roomId", room) else put("botId", bot.orEmpty())
    }

    /** A single global feed, collected only once per selected Host. */
    fun feed(): Flow<BotFeedEvent> = flow {
        client.mux.stream("dsBot/feed").collect { frame ->
            if (frame is MuxEvent.Item) {
                val row =
                    frame.value as? JsonObject ?: throw HostProtocolException("Invalid bot event")
                val event =
                    row.botText("event") ?: throw HostProtocolException("Missing bot event name")
                emit(BotFeedEvent(event, row["data"] as? JsonObject ?: JsonObject(emptyMap())))
            }
        }
    }
}

internal fun JsonElement.botObject() =
    this as? JsonObject ?: throw HostProtocolException("Invalid bot object")

internal fun JsonObject.botText(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.botBool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

internal fun JsonObject.botLong(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.botStrings(key: String) =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

data class BotFeedEvent(val event: String, val data: JsonObject)

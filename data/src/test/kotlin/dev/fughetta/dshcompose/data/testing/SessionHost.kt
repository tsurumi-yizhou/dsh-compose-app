package dev.fughetta.dshcompose.data.testing

import dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository
import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Host-side state for session repository tests; calls cross the production protocol client. */
internal abstract class SessionHost {
    abstract suspend fun list(): List<SessionSummary>

    abstract suspend fun create(cwd: String?, workspaceId: String?, agentPreset: String?): String

    abstract suspend fun rename(id: String, title: String): String

    abstract suspend fun forkSession(id: String, atSeq: Long?): String

    abstract suspend fun forkState(id: String): SessionForkState?

    abstract suspend fun updateQueuedPrompt(id: String, itemId: String, change: QueueChange)

    abstract suspend fun createdAt(id: String): Long?

    abstract suspend fun delete(id: String)

    private val host =
        ProtocolHost(deleteSession = ::delete) { endpoint, args ->
            val request = (args["request"] as? JsonObject).orEmpty()
            fun text(key: String) = request.getValue(key).jsonPrimitive.content
            fun optional(key: String) = request[key]?.jsonPrimitive?.contentOrNull
            when (endpoint) {
                "session/list" ->
                    buildJsonObject {
                        putJsonArray("items") {
                            list().forEach { row ->
                                addJsonObject {
                                    put("sessionId", row.sessionId)
                                    put("cwd", row.cwd)
                                    put("running", row.running)
                                    put("blank", row.blank)
                                    put("agentAvailable", row.agentAvailable)
                                    put("parentSessionId", row.parentSessionId)
                                    put("origin", row.origin)
                                    put("updatedAt", row.updatedAt)
                                    putJsonObject("projections") {
                                        putJsonObject("values") { put("title", row.title) }
                                    }
                                }
                            }
                        }
                    }
                "session/create" ->
                    buildJsonObject {
                        put(
                            "sessionId",
                            create(
                                optional("cwd"),
                                optional("workspaceId"),
                                optional("agentPreset"),
                            ),
                        )
                    }
                "session/rename" ->
                    buildJsonObject { put("title", rename(text("sessionId"), text("title"))) }
                "session/fork" ->
                    buildJsonObject {
                        put(
                            "sessionId",
                            forkSession(text("sessionId"), request["atSeq"]?.jsonPrimitive?.long),
                        )
                    }
                "session/projections" ->
                    forkState(text("sessionId"))?.let { state ->
                        buildJsonObject {
                            putJsonObject("values") {
                                put("title", state.title)
                                putJsonObject("inbox") {
                                    putJsonArray("next-turn") {
                                        state.queuedItemIds.forEach { id ->
                                            addJsonObject { put("id", id) }
                                        }
                                    }
                                }
                            }
                        }
                    } ?: JsonNull
                "session/updateQueue" -> {
                    val action = request.getValue("action").jsonObject
                    val change =
                        when (action.getValue("kind").jsonPrimitive.content) {
                            "remove" -> QueueChange.Remove
                            "steer" -> QueueChange.Steer
                            "edit" ->
                                QueueChange.Edit(
                                    action
                                        .getValue("content")
                                        .jsonArray
                                        .single()
                                        .jsonObject
                                        .getValue("text")
                                        .jsonPrimitive
                                        .content,
                                )
                            else -> error("Unexpected queue action")
                        }
                    updateQueuedPrompt(text("sessionId"), text("itemId"), change)
                    JsonNull
                }
                "session/page" -> {
                    val id =
                        request
                            .getValue("address")
                            .jsonObject
                            .getValue("sessionId")
                            .jsonPrimitive
                            .content
                    val time = createdAt(id)
                    buildJsonObject {
                        putJsonArray("records") {
                            if (time != null)
                                addJsonObject {
                                    putJsonObject("event") {
                                        put("seq", 0)
                                        put("time", time)
                                        put("type", "session/header")
                                        putJsonObject("data") {}
                                    }
                                }
                        }
                        put("hasMore", false)
                    }
                }
                else -> error("Unexpected request: $endpoint")
            }
        }
    val client
        get() = host.client
}

internal suspend fun EngineSessionsRepository.connect(
    host: SessionHost,
    parent: CoroutineScope,
    hostId: String? = null,
) {
    parent.coroutineContext.job.invokeOnCompletion { host.client.close() }
    withContext(Dispatchers.Default) { host.client.mux.ready() }
    connect(host.client, parent, hostId)
}

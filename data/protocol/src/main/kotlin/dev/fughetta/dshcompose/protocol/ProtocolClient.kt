package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.sessions.*
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal val PromptMode.wire: String
    get() =
        when (this) {
            PromptMode.Queue -> "queue"
            PromptMode.Steer -> "steer"
        }

/** Chat operations use the Host's named wire parameters, not positional arguments. */
class ProtocolClient(val connection: HostTransport) : Closeable {
    val mux = Multiplexer(connection)

    suspend fun listSessions(): List<SessionSummary> =
        withContext(Dispatchers.Default) {
            val result =
                connection
                    .call("session/list", buildJsonObject { putJsonObject("_request") {} })
                    .objectValue()
            (result["items"] as? JsonArray ?: throw HostProtocolException("Missing sessions")).map(
                ::decodeSessionSummary,
            )
        }

    suspend fun createSession(
        cwd: String? = null,
        workspaceId: String? = null,
        agentPreset: String? = null,
    ): String =
        request(
                "session/create",
                buildJsonObject {
                    cwd?.let { put("cwd", it) }
                    workspaceId?.let { put("workspaceId", it) }
                    agentPreset?.let { put("agentPreset", it) }
                },
            )
            .objectValue()
            .requiredString("sessionId")

    suspend fun renameSession(sessionId: String, title: String): String =
        request(
                "session/rename",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("title", title)
                },
            )
            .objectValue()
            .requiredString("title")

    suspend fun forkSession(sessionId: String, atSeq: Long? = null): String =
        request(
                "session/fork",
                buildJsonObject {
                    put("sessionId", sessionId)
                    atSeq?.let { put("atSeq", it) }
                },
            )
            .objectValue()
            .requiredString("sessionId")

    suspend fun projections(sessionId: String): JsonObject? =
        request(
                "session/projections",
                buildJsonObject { put("sessionId", sessionId) },
            )
            .let { if (it == JsonNull) null else it.objectValue() }

    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: PromptMode = PromptMode.Queue,
        requestId: String = UUID.randomUUID().toString(),
        images: List<JsonObject> = emptyList(),
        fileReceiptIds: List<String> = emptyList(),
        timeZone: String? = null,
    ): String {
        subagentRoute(sessionId)?.let { route ->
            if (fileReceiptIds.isNotEmpty()) throw HostRemoteException("subagent/file-unsupported")
            return promptSubagent(route, text, images, requestId, mode, timeZone)
        }
        request(
            "session/prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                put("requestId", requestId)
                put("mode", mode.wire)
                putJsonArray("content") {
                    if (text.isNotEmpty())
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    images.forEach { add(it) }
                    fileReceiptIds.forEach { receipt ->
                        addJsonObject {
                            put("type", "file")
                            put("receiptId", receipt)
                        }
                    }
                }
                timeZone?.let { put("clientTimeZone", it) }
            },
        )
        return requestId
    }

    suspend fun cancel(sessionId: String) {
        subagentRoute(sessionId)?.let {
            interruptSubagent(it)
            return
        }
        request("session/cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    suspend fun archiveSession(sessionId: String, stopActivity: Boolean): List<String> =
        archived(
            "workspace/archiveSession",
            buildJsonObject {
                put("sessionId", sessionId)
                put("stopActivity", stopActivity)
            },
        )

    suspend fun unarchiveSession(sessionId: String): List<String> =
        archived(
            "workspace/unarchiveSession",
            buildJsonObject { put("sessionId", sessionId) },
        )

    private suspend fun archived(endpoint: String, value: JsonObject): List<String> =
        (request(endpoint, value).objectValue()["archivedSessionIds"] as? JsonArray
                ?: throw HostProtocolException("Missing archived session ids"))
            .map {
                (it as? JsonPrimitive)?.contentOrNull
                    ?: throw HostProtocolException("Invalid session id")
            }

    suspend fun history(sessionId: String, throughSeq: Long, beforeSeq: Long? = null): HistoryPage =
        withContext(Dispatchers.Default) {
            request(
                    "session/page",
                    buildJsonObject {
                        put("address", sessionAddress(sessionId))
                        put("throughSeq", throughSeq)
                        beforeSeq?.let { put("beforeSeq", it) }
                        put("maxMessages", 100)
                        putJsonObject("turnWindow") {
                            put("minMessages", 30)
                            put("minTurns", 2)
                        }
                    },
                )
                .objectValue()
                .let { HistoryPage(it.records(), it.boolean("hasMore") == true) }
        }

    suspend fun deleteSessionPermanently(sessionId: String) =
        connection.deleteSessionPermanently(sessionId)

    private suspend fun request(endpoint: String, value: JsonObject): JsonElement =
        connection.call(endpoint, buildJsonObject { put("request", value) })

    override fun close() {
        clearSubagentRoutes()
        mux.close()
        connection.close()
    }
}

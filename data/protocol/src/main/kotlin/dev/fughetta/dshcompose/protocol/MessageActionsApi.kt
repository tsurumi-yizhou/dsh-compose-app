package dev.fughetta.dshcompose.protocol

import kotlinx.serialization.json.*

enum class FeedbackRating {
    Positive,
    Negative;

    val wire
        get() = name.lowercase()

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.wire == value }
    }
}

enum class FeedbackCategory(val wire: String) {
    TaskResult("task-result"),
    InstructionFollowing("instruction-following"),
    ProductInteraction("product-interaction"),
    ServiceStability("service-stability"),
    ResourceCost("resource-cost"),
    SecurityPrivacyPermission("security-privacy-permission"),
    Other("other"),
}

data class MessageFeedback(
    val messageId: String,
    val rating: FeedbackRating,
    val version: String,
    val note: String? = null,
    val category: FeedbackCategory? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    companion object {
        internal fun parse(value: JsonElement?): MessageFeedback? {
            val row = value as? JsonObject ?: return null
            return MessageFeedback(
                row.string("messageId") ?: return null,
                FeedbackRating.parse(row.string("rating")) ?: return null,
                row.string("version") ?: return null,
                row.string("note"),
                FeedbackCategory.entries.firstOrNull { it.wire == row.string("category") },
                row.long("createdAt"),
                row.long("updatedAt"),
            )
        }
    }
}

class MessageFeedbackException(
    val code: String,
    val current: MessageFeedback?,
    val details: JsonObject,
) : HostException("messageFeedback/$code")

private fun feedbackValue(result: JsonElement): JsonElement {
    val row = result.objectValue()
    if (row.boolean("ok") == true) return row["value"] ?: JsonNull
    val error =
        row["error"] as? JsonObject ?: throw HostProtocolException("Invalid feedback response")
    throw MessageFeedbackException(
        error.string("code") ?: "unknown",
        MessageFeedback.parse(error["current"]),
        error,
    )
}

suspend fun ProtocolClient.listMessageFeedback(sessionId: String): List<MessageFeedback> {
    val result =
        feedbackValue(
                connection.call(
                    "messageFeedback/list",
                    buildJsonObject { putJsonObject("request") { put("sessionId", sessionId) } },
                ),
            )
            .objectValue()
    return (result["items"] as? JsonArray ?: throw HostProtocolException("Missing feedback items"))
        .map { MessageFeedback.parse(it) ?: throw HostProtocolException("Invalid feedback item") }
}

suspend fun ProtocolClient.putMessageFeedback(
    sessionId: String,
    messageId: String,
    rating: FeedbackRating,
    ifVersion: String?,
    note: String? = null,
    category: FeedbackCategory? = null,
): MessageFeedback {
    val result =
        feedbackValue(
            connection.call(
                "messageFeedback/put",
                buildJsonObject {
                    putJsonObject("request") {
                        put("sessionId", sessionId)
                        put("messageId", messageId)
                        put("rating", rating.wire)
                        put("ifVersion", ifVersion?.let(::JsonPrimitive) ?: JsonNull)
                        note?.let { put("note", it) }
                        category?.let { put("category", it.wire) }
                    }
                },
            ),
        )
    return MessageFeedback.parse(result) ?: throw HostProtocolException("Invalid feedback item")
}

suspend fun ProtocolClient.deleteMessageFeedback(
    sessionId: String,
    messageId: String,
    ifVersion: String,
) {
    feedbackValue(
        connection.call(
            "messageFeedback/delete",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", sessionId)
                    put("messageId", messageId)
                    put("ifVersion", ifVersion)
                }
            },
        ),
    )
}

fun forkPointAfterReply(events: Iterable<SessionRecord>, replySeq: Long): Long? {
    var found = false
    for (event in events) {
        if (!found) {
            if (event.seq < replySeq) continue
            val message = event.data["message"] as? JsonObject
            val content = message?.get("content") as? JsonArray
            val text =
                content.orEmpty().any { (it as? JsonObject)?.string("text")?.isNotBlank() == true }
            if (event.seq != replySeq || event.type != "assistant/message" || !text) return null
            found = true
            continue
        }
        when (event.type) {
            "turn/end" ->
                return event.seq.takeUnless {
                    (event.data["reason"] as? JsonObject)?.string("kind") == "error"
                }
            "assistant/message",
            "tool/call",
            "tool/result",
            "llm/retry" -> return null
        }
    }
    return null
}

/** Receives the contiguous records immediately preceding a prompt, newest first. */
fun forkPointBeforePrompt(beforeDescending: Iterable<SessionRecord>, promptSeq: Long): Long? {
    var expected = promptSeq - 1
    for (event in beforeDescending) {
        if (event.seq != expected) return null
        expected--
        if (
            event.type !in
                setOf("agent/inbox/spliced", "turn/start", "step/start", "system/message")
        )
            return event.seq
    }
    return null
}

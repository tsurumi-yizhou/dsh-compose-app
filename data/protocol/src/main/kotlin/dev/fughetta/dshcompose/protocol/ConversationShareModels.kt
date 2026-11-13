package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import dev.fughetta.dshcompose.domain.sharing.shareLine
import dev.fughetta.dshcompose.domain.sharing.sharePrefix
import java.time.Instant
import java.util.Base64
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

data class SharedConversationEntry(val body: JsonObject) {
    val type
        get() = body.npString("type").orEmpty()

    val time
        get() = (body["t"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0L

    val duration
        get() = (body["d"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0L

    val text
        get() = body.npString("text").orEmpty()
}

data class SharedConversation(
    val body: JsonObject,
    val entries: PersistentList<SharedConversationEntry>,
) {
    val title
        get() = body.npString("title").orEmpty()

    val start
        get() = Instant.parse(body.npString("start"))

    val span
        get() = entries.maxOfOrNull { (it.time + it.duration).coerceAtLeast(it.time) } ?: 0L

    val messageCount
        get() = entries.count { it.type == "user" || it.type == "reply" && it.text.isNotBlank() }

    companion object {
        fun parse(raw: JsonObject): SharedConversation {
            if (
                (raw["v"] as? JsonPrimitive)?.intOrNull != 2 ||
                    (raw["schema"] as? JsonPrimitive)?.intOrNull != 1 ||
                    raw.npString("kind") != "conversation"
            )
                throw ShareFormatException("share/invalid-conversation")
            if (runCatching { Instant.parse(raw.npString("start")) }.getOrNull() == null)
                throw ShareFormatException("share/invalid-conversation")
            val entries =
                raw["entries"] as? JsonArray
                    ?: throw ShareFormatException("share/invalid-conversation")
            if (entries.size > 20_000) throw ShareFormatException("share/too-large")
            val parsed =
                entries
                    .map { value ->
                        val entry =
                            value as? JsonObject
                                ?: throw ShareFormatException("share/invalid-conversation")
                        val type = entry.npString("type")
                        if (
                            type !in
                                setOf(
                                    "turn",
                                    "user",
                                    "reply",
                                    "tool",
                                    "notice",
                                    "todos",
                                    "goal",
                                    "command",
                                )
                        )
                            throw ShareFormatException("share/invalid-conversation")
                        SharedConversationEntry(
                            buildJsonObject {
                                entry.forEach { (key, item) ->
                                    val limit =
                                        when (key) {
                                            "text",
                                            "think",
                                            "plain" ->
                                                if (type in setOf("user", "reply")) 400_000
                                                else 8000
                                            "result" -> 64_000
                                            "summary" -> 500
                                            "error" -> 2000
                                            else -> 400_000
                                        }
                                    put(
                                        key,
                                        if (item is JsonPrimitive && item.isString)
                                            JsonPrimitive(sharePrefix(item.content, limit))
                                        else item,
                                    )
                                }
                            },
                        )
                    }
                    .toPersistentList()
            val body =
                JsonObject(
                    raw +
                        mapOf(
                            "title" to
                                JsonPrimitive(shareLine(raw.npString("title"), 120).orEmpty()),
                            "entries" to JsonArray(parsed.map { it.body }),
                        ),
                )
            return SharedConversation(body, parsed)
        }
    }
}

class SharedConversationAsset(
    val id: String,
    val mime: String,
    val bytes: ByteArray,
    val width: Int?,
    val height: Int?,
) {
    fun json() = buildJsonObject {
        put("type", mime)
        width?.let { put("w", it) }
        height?.let { put("h", it) }
        put("data", Base64.getEncoder().encodeToString(bytes))
    }

    override fun toString() = "SharedConversationAsset($id,${bytes.size})"
}

data class DecodedSharedDocument(
    val document: SharedDocument,
    val code: String,
    val assets: PersistentMap<String, SharedConversationAsset>,
)

fun decodeSharedDocument(text: String): DecodedSharedDocument {
    val envelope = ShareCodeCodec.envelope(text)
    val document = SharedDocument.parse(ShareCodeCodec.decode(envelope.code))
    return DecodedSharedDocument(
        document,
        envelope.code,
        sharedConversationAssets(document, envelope.assets),
    )
}

fun sharedConversationAssets(
    document: SharedDocument,
    raw: JsonObject?,
): PersistentMap<String, SharedConversationAsset> {
    val conversation = (document as? SharedDocument.Conversation)?.conversation
    val wanted =
        conversation
            ?.entries
            ?.filter { it.type == "user" }
            ?.flatMap { entry ->
                (entry.body["images"] as? JsonArray).orEmpty().take(20).mapNotNull {
                    (it as? JsonObject)?.npString("id")
                }
            }
            ?.toSet()
            .orEmpty()
    val assets = persistentMapOf<String, SharedConversationAsset>().builder()
    raw?.forEach { (id, value) ->
        if (
            id in wanted &&
                value is JsonObject &&
                value.npString("type") in
                    setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        ) {
            val bytes = runCatching {
                Base64.getDecoder().decode(value.npString("data"))
            }
                .getOrNull()
            if (bytes != null && ShareCodeCodec.assetId(bytes) == id)
                assets[id] =
                    SharedConversationAsset(
                        id,
                        value.npString("type")!!,
                        bytes,
                        (value["w"] as? JsonPrimitive)?.intOrNull,
                        (value["h"] as? JsonPrimitive)?.intOrNull,
                    )
        }
    }
    return assets.build()
}

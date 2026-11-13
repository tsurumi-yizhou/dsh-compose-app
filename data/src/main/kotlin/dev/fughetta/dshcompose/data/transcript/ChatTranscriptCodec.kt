package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.attachments.AttachmentReference
import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.chat.ToolPresentation
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import java.util.TreeMap
import kotlinx.serialization.json.*

internal fun content(message: JsonObject): Content {
    val parts = (message["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    return Content(
        parts
            .filter { it.string("type") == "text" }
            .joinToString("") { it.string("text").orEmpty() },
        parts
            .filter { it.string("type") == "reasoning" }
            .joinToString("") { it.string("text").orEmpty() },
        parts
            .filter { it.string("type") in setOf("image", "file") }
            .map { part ->
                val ref = part["attachment"] as? JsonObject ?: JsonObject(emptyMap())
                ChatAttachment(
                    part.string("type")!!,
                    ref.string("attachmentId"),
                    ref.string("name"),
                    ref.string("mediaType"),
                    ref.long("bytes"),
                    AttachmentReference(ref),
                )
            },
        parts.filter { it.string("type") == "tool-call" },
    )
}

internal class Draft(val id: String, val turn: Long, val step: Long, val startedAfterSeq: Long) {
    val rowKey
        get() = "m$turn.$step"

    var nextIndex = 0L
    var streaming = true
    private val blocks = TreeMap<Long, DraftBlock>()

    fun apply(chunk: JsonObject) {
        val index = chunk.long("index") ?: 0
        when (chunk.string("type")) {
            "block-start" -> blocks[index] = DraftBlock(chunk.string("blockType") ?: "unknown")
            "text-delta",
            "reasoning-delta",
            "tool-call-delta" -> {
                val type =
                    when (chunk.string("type")) {
                        "text-delta" -> "text"
                        "reasoning-delta" -> "reasoning"
                        else -> "tool-call"
                    }
                val block = blocks.getOrPut(index) { DraftBlock(type) }
                block.text.append(
                    chunk.string(if (type == "tool-call") "argumentsDelta" else "text").orEmpty(),
                )
                chunk.string("id")?.let { block.id = it }
                chunk.string("name")?.let { block.name = it }
            }
            "block-end" ->
                blocks.getOrPut(index) { DraftBlock("unknown") }.finished =
                    chunk["block"] as? JsonObject
            "finish" -> streaming = false
        }
    }

    fun items(useRowKey: Boolean): List<ChatItem> {
        val parts = blocks.map { (index, block) ->
            block.finished
                ?: buildJsonObject {
                    put("type", block.type)
                    if (block.type == "tool-call") {
                        put("id", block.id ?: "$id#$index")
                        put("name", block.name.orEmpty())
                        put("arguments", block.text.toString())
                    } else put("text", block.text.toString())
                }
        }
        val content = content(buildJsonObject { put("content", JsonArray(parts)) })
        val finishedCalls =
            blocks.values
                .mapNotNull {
                    it.finished?.takeIf { part -> part.string("type") == "tool-call" }?.string("id")
                }
                .toSet()
        return buildList {
            if (
                content.text.isNotEmpty() ||
                    content.reasoning.isNotEmpty() ||
                    content.attachments.isNotEmpty()
            ) {
                add(
                    ChatItem(
                        if (useRowKey) rowKey else "draft:$id",
                        ChatRole.Assistant,
                        content.text,
                        content.reasoning,
                        content.attachments,
                        startedAfterSeq,
                        streaming = streaming,
                        turnId = turn.toString(),
                    ),
                )
            }
            content.calls.forEach { call ->
                val callId = call.requiredString("id")
                add(
                    ChatItem(
                        "tool:$callId",
                        ChatRole.Tool,
                        seq = startedAfterSeq,
                        streaming = streaming,
                        tool =
                            ToolPresentation(
                                callId,
                                call.string("name").orEmpty(),
                                call.string("arguments").orEmpty(),
                                if (callId in finishedCalls) ToolState.Running
                                else ToolState.Streaming,
                            ),
                        turnId = turn.toString(),
                    ),
                )
            }
        }
    }
}

internal class DraftBlock(
    val type: String,
    val text: StringBuilder = StringBuilder(),
    var id: String? = null,
    var name: String? = null,
    var finished: JsonObject? = null,
)

internal fun TreeMap<Long, SessionRecord>.firstKeyOrNull(): Long? =
    if (isEmpty()) null else firstKey()

/** Stored assistant stream runs use the same chunks as live frames. */
internal fun expandChunks(value: JsonElement?): List<JsonObject> = buildList {
    for (raw in (value as? JsonArray).orEmpty()) {
        val run = raw as? JsonObject ?: continue
        val type = run.string("type")
        if (type == "chunk") {
            (run["chunk"] as? JsonObject)?.let { add(it) }
            continue
        }
        val chunkType =
            when (type) {
                "text-chunks" -> "text-delta"
                "reasoning-chunks" -> "reasoning-delta"
                "tool-call-chunks" -> "tool-call-delta"
                else -> continue
            }
        for (text in
            (run[if (type == "tool-call-chunks") "args" else "texts"] as? JsonArray).orEmpty()) add(
            buildJsonObject {
                put("type", chunkType)
                put("index", run.long("index") ?: 0)
                put(if (type == "tool-call-chunks") "argumentsDelta" else "text", text)
                run["id"]?.let { put("id", it) }
                run["name"]?.let { put("name", it) }
            },
        )
    }
}

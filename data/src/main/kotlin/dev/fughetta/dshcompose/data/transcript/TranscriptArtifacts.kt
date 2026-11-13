package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.chat.ToolPresentation
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.links.workspaceLinkPath
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifact
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifactKind
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifactOrigin
import dev.fughetta.dshcompose.domain.sessions.TranscriptChangeBlock
import dev.fughetta.dshcompose.domain.sessions.TranscriptFileChange
import dev.fughetta.dshcompose.domain.sessions.transcriptArtifactKind
import dev.fughetta.dshcompose.domain.transcript.*
import dev.fughetta.dshcompose.protocol.*
import java.nio.file.Paths
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

private fun candidate(path: String) =
    path.substringAfterLast('.').lowercase() in setOf("ics", "vcf") ||
        transcriptArtifactKind(path) !in
            setOf(
                TranscriptArtifactKind.Markdown,
                TranscriptArtifactKind.Text,
                TranscriptArtifactKind.Other,
            )

fun transcriptHostPath(path: String, cwd: String? = null): String {
    if (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)) return path
    val joined = if (path.startsWith('/') || cwd.isNullOrBlank()) path else "$cwd/$path"
    return if (joined.startsWith('/')) Paths.get(joined).normalize().toString() else joined
}

/**
 * Current-turn working set only; finished traces keep file descriptors, never duplicate file
 * bodies.
 */
internal class TranscriptArtifactFold {
    private val calls = linkedMapOf<String, Pair<String, String>>()
    private val successes = mutableListOf<String>()
    private val declared = linkedMapOf<String, TranscriptArtifact>()
    private val written = linkedMapOf<String, TranscriptArtifact>()
    private val changes = linkedMapOf<String, TranscriptFileChange>()

    private data class Known(val first: Int, var lines: List<String>)

    private val known = mutableMapOf<String, Known>()
    private var reply: String? = null
    private var turn = ""

    fun start(id: String) {
        turn = id
        calls.clear()
        successes.clear()
        declared.clear()
        written.clear()
        changes.clear()
        known.clear()
        reply = null
    }

    fun apply(record: SessionRecord) {
        val data = record.data
        when (record.type) {
            "tool/call" -> {
                val id = data.string("callId") ?: return
                calls[id] = data.string("name").orEmpty() to data.string("arguments").orEmpty()
            }
            "deliverables/presented" -> {
                if (data.long("turn")?.toString() != turn || data.string("callId").isNullOrEmpty())
                    return
                (data["files"] as? JsonArray).orEmpty().forEach { raw ->
                    val row = raw as? JsonObject ?: return@forEach
                    val path = row.string("path")?.takeIf { it.isNotBlank() } ?: return@forEach
                    val description = row["description"]
                    if (
                        description != null &&
                            description != JsonNull &&
                            (description !is JsonPrimitive || !description.isString)
                    )
                        return@forEach
                    declared[path] =
                        TranscriptArtifact(
                            path,
                            TranscriptArtifactOrigin.Delivered,
                            turn,
                            (description as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
                                it.isNotEmpty()
                            },
                            record.seq,
                        )
                }
            }
            "assistant/message" -> {
                val message = data["message"] as? JsonObject ?: return
                val text =
                    (message["content"] as? JsonArray)
                        .orEmpty()
                        .mapNotNull {
                            (it as? JsonObject)
                                ?.takeIf { part -> part.string("type") == "text" }
                                ?.string("text")
                        }
                        .joinToString("\n")
                if (text.isNotBlank()) reply = text
            }
            "tool/result" -> {
                val message = data["message"] as? JsonObject ?: return
                if (message.boolean("isError") == true) return
                val id = message.string("toolCallId") ?: return
                val call = calls[id] ?: return
                val text =
                    (message["content"] as? JsonArray)
                        .orEmpty()
                        .mapNotNull {
                            (it as? JsonObject)
                                ?.takeIf { part -> part.string("type") == "text" }
                                ?.string("text")
                        }
                        .joinToString("\n")
                successes += text
                val item =
                    ChatItem(
                        "tool:$id",
                        ChatRole.Tool,
                        text,
                        tool =
                            ToolPresentation(
                                id,
                                call.first,
                                call.second,
                                ToolState.Succeeded,
                                meta = data["meta"]?.toToolPayload(),
                            ),
                    )
                if (call.first !in setOf("read", "write", "edit", "str_replace_editor")) return
                val args =
                    if (item.tool?.state == ToolState.Streaming) null
                    else
                        runCatching {
                            Json.parseToJsonElement(item.tool!!.arguments).toToolPayload()
                                as? dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj
                        }
                            .getOrNull()
                val view = ToolPresentationView(item, args)
                val meta = data["meta"]?.toToolPayload()?.toJsonElement() as? JsonObject
                val path = meta?.string("path") ?: view.path
                if (path != null) {
                    view.read?.let { window ->
                        known[path] = Known(window.firstLine, window.code.split('\n'))
                    }
                    val created =
                        call.first == "write" && meta?.string("operation") == "create" ||
                            call.first == "str_replace_editor" && view.arg("command") == "create"
                    if (created && candidate(path))
                        written.putIfAbsent(
                            path,
                            TranscriptArtifact(
                                path,
                                TranscriptArtifactOrigin.Written,
                                turn,
                                seq = record.seq,
                            ),
                        )
                    val rawBlocks =
                        view.changes
                            ?: view.written?.let {
                                listOf(
                                    TranscriptChangeBlock(
                                        null,
                                        it,
                                        newStart = if (created) 1 else null,
                                    ),
                                )
                            }
                    val blocks = rawBlocks?.map { block ->
                        val window = known[path]
                        val old = block.old?.split('\n')
                        if (window == null || old.isNullOrEmpty()) block
                        else {
                            val at = uniqueLineMatch(window.lines, old)
                            if (at == null) block
                            else {
                                val line = window.first + at
                                window.lines =
                                    window.lines.take(at) +
                                        block.new.split('\n') +
                                        window.lines.drop(at + old.size)
                                block.copy(oldStart = line, newStart = line)
                            }
                        }
                    }
                    if (view.written != null) known[path] = Known(1, view.written!!.split('\n'))
                    if (blocks != null) {
                        val old = changes[path]
                        changes[path] =
                            TranscriptFileChange(
                                path,
                                (old?.callIds ?: persistentListOf()).adding(id),
                                (old?.blocks ?: persistentListOf()).addingAll(blocks),
                            )
                    }
                }
            }
        }
    }

    private fun uniqueLineMatch(lines: List<String>, needle: List<String>): Int? {
        if (needle.isEmpty() || needle.size > lines.size) return null
        val failure = IntArray(needle.size)
        var matched = 0
        for (i in 1 until needle.size) {
            while (matched > 0 && needle[i] != needle[matched]) matched = failure[matched - 1]
            if (needle[i] == needle[matched]) matched++
            failure[i] = matched
        }
        matched = 0
        var found: Int? = null
        for (i in lines.indices) {
            while (matched > 0 && lines[i] != needle[matched]) matched = failure[matched - 1]
            if (lines[i] == needle[matched]) matched++
            if (matched == needle.size) {
                if (found != null) return null
                found = i - needle.size + 1
                matched = failure[matched - 1]
            }
        }
        return found
    }

    fun finish(
        cwd: String? = null,
    ): Pair<PersistentList<TranscriptArtifact>, PersistentList<TranscriptFileChange>> {
        val files = linkedMapOf<String, TranscriptArtifact>()
        if (declared.isNotEmpty())
            declared.values.forEach { file ->
                val path = transcriptHostPath(file.path, cwd)
                files[path] = file.copy(path = path)
            }
        else {
            written.values.forEach { file ->
                val path = transcriptHostPath(file.path, cwd)
                files[path] = file.copy(path = path)
            }
            reply?.let { text ->
                Regex("\\]\\(\\s*(?:<([^>\\n]+)>|([^)\\s]+))").findAll(text).forEach { match ->
                    val path =
                        workspaceLinkPath(
                            match.groupValues[1].ifEmpty { match.groupValues[2] },
                            cwd,
                        ) ?: return@forEach
                    val name = path.substringAfterLast('/').substringAfterLast('\\')
                    if (
                        candidate(path) &&
                            (calls.values.any { it.second.contains(name) } ||
                                successes.any { it.contains(name) })
                    )
                        files.putIfAbsent(
                            path,
                            TranscriptArtifact(path, TranscriptArtifactOrigin.Linked, turn),
                        )
                }
            }
        }
        val result = files.values.toPersistentList() to changes.values.toPersistentList()
        calls.clear()
        successes.clear()
        declared.clear()
        written.clear()
        changes.clear()
        known.clear()
        reply = null
        return result
    }
}

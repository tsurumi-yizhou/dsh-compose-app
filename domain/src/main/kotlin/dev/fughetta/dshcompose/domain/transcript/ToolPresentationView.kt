package dev.fughetta.dshcompose.domain.transcript

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.sessions.TranscriptChangeBlock

enum class TranscriptDiffKind {
    Hunk,
    Added,
    Removed,
    Context,
    Meta,
}

data class TranscriptDiffLine(
    val kind: TranscriptDiffKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class TranscriptReadWindow(val code: String, val firstLine: Int, val language: String?)

fun looksLikeTranscriptDiff(text: String) =
    Regex("^@@ -\\d", RegexOption.MULTILINE).containsMatchIn(text) ||
        Regex("^(---|\\+\\+\\+) ", RegexOption.MULTILINE).containsMatchIn(text) &&
            Regex("^[+-](?![+-]{2})", RegexOption.MULTILINE).containsMatchIn(text)

fun parseTranscriptDiff(text: String): List<TranscriptDiffLine> {
    var old: Int? = null
    var new: Int? = null
    val header = Regex("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)")
    return text
        .lineSequence()
        .map { line ->
            val kind =
                when {
                    line.startsWith("@@") -> TranscriptDiffKind.Hunk
                    line.startsWith("+++") ||
                        line.startsWith("---") ||
                        line.startsWith("diff ") ||
                        line.startsWith("index ") -> TranscriptDiffKind.Meta
                    line.startsWith('+') -> TranscriptDiffKind.Added
                    line.startsWith('-') -> TranscriptDiffKind.Removed
                    else -> TranscriptDiffKind.Context
                }
            if (kind == TranscriptDiffKind.Hunk) {
                val found = header.find(line)
                old = found?.groupValues?.get(1)?.toIntOrNull()
                new = found?.groupValues?.get(2)?.toIntOrNull()
            }
            val result =
                TranscriptDiffLine(
                    kind,
                    line,
                    if (kind == TranscriptDiffKind.Removed || kind == TranscriptDiffKind.Context)
                        old
                    else null,
                    if (kind == TranscriptDiffKind.Added || kind == TranscriptDiffKind.Context) new
                    else null,
                )
            if (kind == TranscriptDiffKind.Added || kind == TranscriptDiffKind.Context)
                new = new?.plus(1)
            if (kind == TranscriptDiffKind.Removed || kind == TranscriptDiffKind.Context)
                old = old?.plus(1)
            result
        }
        .toList()
        .dropLastWhile { it.kind == TranscriptDiffKind.Context && it.text.isEmpty() }
}

/**
 * LCS work is bounded independently from transcript size, and runs only when a visible diff opens.
 */
fun transcriptLineDiff(old: String, new: String): List<TranscriptDiffLine> {
    val a = if (old.isEmpty()) emptyList() else old.split('\n')
    val b = if (new.isEmpty()) emptyList() else new.split('\n')
    if (a.size.toLong() * b.size > 160000)
        return a.map { TranscriptDiffLine(TranscriptDiffKind.Removed, "-$it") } +
            b.map { TranscriptDiffLine(TranscriptDiffKind.Added, "+$it") }
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) dp[i][j] =
        if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    val out = ArrayList<TranscriptDiffLine>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        if (a[i] == b[j]) {
            out += TranscriptDiffLine(TranscriptDiffKind.Context, " ${a[i]}")
            i++
            j++
        } else if (dp[i + 1][j] >= dp[i][j + 1])
            out += TranscriptDiffLine(TranscriptDiffKind.Removed, "-${a[i++]}")
        else out += TranscriptDiffLine(TranscriptDiffKind.Added, "+${b[j++]}")
    }
    while (i < a.size) out += TranscriptDiffLine(TranscriptDiffKind.Removed, "-${a[i++]}")
    while (j < b.size) out += TranscriptDiffLine(TranscriptDiffKind.Added, "+${b[j++]}")
    return out
}

/**
 * One visible tool's parsed fields. The caller remembers this by the immutable ChatItem identity.
 */
class ToolPresentationView(val item: ChatItem, val arguments: ToolPayload.Obj?) {
    val tool = item.tool ?: error("Tool row required")

    fun arg(key: String): String? {
        arguments?.text(key)?.let {
            return it
        }
        if (arguments != null) return null
        val escaped = Regex.escape(key)
        val partial =
            Regex("\"$escaped\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)")
                .find(tool.arguments.take(16384))
                ?.groupValues
                ?.get(1) ?: return null
        return partial.replace(Regex("\\\\(u[0-9a-fA-F]{4}|.)")) { match ->
            when (val value = match.groupValues[1]) {
                "n" -> "\n"
                "t" -> "\t"
                "r" -> ""
                else ->
                    if (value.startsWith('u') && value.length == 5)
                        value.drop(1).toInt(16).toChar().toString()
                    else value
            }
        }
    }

    val terminal
        get() = tool.name in setOf("bash", "pwsh", "job_output")

    val path
        get() = arg("file_path") ?: arg("path")

    val command
        get() = if (terminal) arg("command") else null

    val fileTool
        get() = tool.name in setOf("read", "write", "edit", "str_replace_editor", "read_image")

    val sandboxBlocked
        get() =
            tool.state == ToolState.Failed &&
                (item.text.contains("no sandbox backend is usable") ||
                    tool.failure?.toString()?.contains("no sandbox backend is usable") == true)

    val failureText
        get() =
            if (tool.state != ToolState.Failed) null
            else
                item.text.takeIf { it.isNotBlank() }
                    ?: tool.failure?.get("reason")?.primitiveContent
                    ?: tool.failure?.get("message")?.primitiveContent
                    ?: tool.failure?.get("code")?.primitiveContent

    val summary: String by lazy {
        val value =
            when (tool.name) {
                "bash",
                "pwsh" -> arg("command") ?: arg("description")
                "present" ->
                    arguments
                        ?.arrayItems("files")
                        ?.mapNotNull { it.string("path")?.substringAfterLast('/') }
                        ?.joinToString(" · ") ?: path
                "read",
                "write",
                "edit",
                "read_image",
                "str_replace_editor" -> path
                "grep" -> listOfNotNull(arg("pattern"), arg("path")).joinToString(" · ")
                "glob" -> arg("pattern")
                "web_fetch" -> arg("url")?.replace(Regex("^https?://"), "")
                "web_search" ->
                    arguments?.arrayItems("queries")?.mapNotNull { it.asText }?.joinToString(" · ")
                        ?: arg("query")
                "deep_research" -> arg("question")
                "parse_document",
                "ocr" -> arg("file") ?: arg("question")
                "todo_write" -> arguments?.arrayItems("todos")?.size?.toString()
                "job_output",
                "job_kill" -> arg("job_id") ?: arg("id")
                "ask_user_question" ->
                    arguments?.arrayItems("questions")?.firstOrNull()?.string("question")
                "subagent",
                "send_message",
                "workflow",
                "ralph" -> arg("description") ?: arg("prompt")
                "skill" -> arg("name") ?: arg("skill")
                else ->
                    sequenceOf(
                            "command",
                            "file_path",
                            "path",
                            "pattern",
                            "query",
                            "url",
                            "description",
                            "prompt",
                            "name",
                        )
                        .mapNotNull(::arg)
                        .firstOrNull { it.isNotBlank() }
                        ?: arguments
                            ?.fields
                            ?.values
                            ?.filterIsInstance<ToolPayload.Text>()
                            ?.firstOrNull { it.value.isNotBlank() }
                            ?.value
            }
        value.orEmpty().trim().replace(Regex("\\s+"), " ")
    }
    val changes: List<TranscriptChangeBlock>? by lazy {
        val meta = tool.meta?.arrayItems("diffs")
        val blocks =
            meta
                ?.mapNotNull { row ->
                    val obj = row as? ToolPayload.Obj ?: return@mapNotNull null
                    val new = obj.string("newText") ?: return@mapNotNull null
                    TranscriptChangeBlock(obj.string("oldText"), new)
                }
                ?.takeIf { it.isNotEmpty() }
        blocks
            ?: when {
                tool.name == "edit" ->
                    arg("old_string")?.let { old ->
                        arg("new_string")?.let { listOf(TranscriptChangeBlock(old, it)) }
                    }
                tool.name == "str_replace_editor" && arg("command") == "str_replace" ->
                    arg("old_str")?.let { old ->
                        arg("new_str")?.let { listOf(TranscriptChangeBlock(old, it)) }
                    }
                tool.name == "str_replace_editor" && arg("command") == "insert" ->
                    arg("new_str")?.let { text ->
                        val line = arguments?.get("insert_line")?.primitiveContent?.toIntOrNull()
                        listOf(
                            TranscriptChangeBlock(
                                null,
                                text,
                                oldStart = line,
                                newStart = line?.plus(1),
                            ),
                        )
                    }
                else -> null
            }
    }
    val written: String?
        get() =
            when {
                tool.name == "write" -> arg("content")
                tool.name == "str_replace_editor" && arg("command") == "create" -> arg("file_text")
                else -> null
            }

    val diff: List<TranscriptDiffLine>? by lazy {
        changes?.flatMapIndexed { index, block ->
            (if (index > 0) listOf(TranscriptDiffLine(TranscriptDiffKind.Hunk, "@@ ${index+1} @@"))
            else emptyList()) +
                numberedTranscriptDiff(
                    transcriptLineDiff(block.old.orEmpty(), block.new),
                    block.oldStart,
                    block.newStart,
                )
        } ?: item.text.takeIf(::looksLikeTranscriptDiff)?.let(::parseTranscriptDiff)
    }
    val read: TranscriptReadWindow? by lazy {
        if (tool.name != "read" || tool.state != ToolState.Succeeded) return@lazy null
        val meta = tool.meta
        val lines = meta?.arrayItems("lines")
        val pairs = lines?.mapNotNull { row ->
            val obj = row as? ToolPayload.Obj ?: return@mapNotNull null
            val number = obj["number"]?.primitiveContent?.toLongOrNull() ?: return@mapNotNull null
            val text = obj.string("text") ?: return@mapNotNull null
            number.toInt() to text
        }
        if (!pairs.isNullOrEmpty())
            TranscriptReadWindow(
                pairs.joinToString("\n") { it.second },
                pairs.first().first,
                meta?.string("lang"),
            )
        else {
            val result = mutableListOf<String>()
            var first: Int? = null
            val pattern = Regex("^(\\d+): ?(.*)$")
            for (line in item.text.lineSequence()) {
                val match = pattern.matchEntire(line)
                if (match == null) {
                    if (first != null) break
                    continue
                }
                if (first == null) first = match.groupValues[1].toIntOrNull()
                result += match.groupValues[2]
            }
            first?.let { TranscriptReadWindow(result.joinToString("\n"), it, null) }
        }
    }
}

fun stripToolAnsi(text: String): String =
    text
        .replace(Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"), "")
        .replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")

fun numberedTranscriptDiff(
    lines: List<TranscriptDiffLine>,
    oldStart: Int?,
    newStart: Int?,
): List<TranscriptDiffLine> {
    var old = oldStart
    var new = newStart
    return lines.map { line ->
        when (line.kind) {
            TranscriptDiffKind.Added -> line.copy(newLine = new).also { new = new?.plus(1) }
            TranscriptDiffKind.Removed -> line.copy(oldLine = old).also { old = old?.plus(1) }
            TranscriptDiffKind.Context ->
                line.copy(oldLine = old, newLine = new).also {
                    old = old?.plus(1)
                    new = new?.plus(1)
                }
            else -> line
        }
    }
}

fun transcriptPatch(path: String, blocks: List<TranscriptChangeBlock>): String = buildString {
    val created = blocks.all { it.old == null }
    append(if (created) "--- /dev/null\n" else "--- a/$path\n")
    append("+++ b/$path\n")
    blocks.forEach { block ->
        val lines = transcriptLineDiff(block.old.orEmpty(), block.new)
        val old = lines.count {
            it.kind == TranscriptDiffKind.Removed || it.kind == TranscriptDiffKind.Context
        }
        val new = lines.count {
            it.kind == TranscriptDiffKind.Added || it.kind == TranscriptDiffKind.Context
        }
        append(
            "@@ -${if(old==0)0 else block.oldStart?:1},$old +${if(new==0)0 else block.newStart?:1},$new @@\n",
        )
        lines.forEach {
            append(it.text.ifEmpty { " " })
            append('\n')
        }
    }
}

private fun ToolPayload.string(key: String): String? = this[key]?.primitiveContent

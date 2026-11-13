package dev.fughetta.dshcompose.domain.media

class MediaHeaderException(val code: String) : Exception(code)

data class MediaHeaderRead(
    val parameters: MediaParameters?,
    val prompt: String?,
    val error: String?,
)

/**
 * The host plugin's ordinary-message grammar. The Chinese words are wire syntax in every locale.
 */
object MediaGenerationHeader {
    private val spaces =
        "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
    private val aspect = Regex("[1-9][0-9]?:[1-9][0-9]?")
    private val aspectShape = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?:[+-]?[0-9]+(?:\\.[0-9]+)?")
    private val countShape = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?[$spaces]*张")
    private val secondsShape = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?[$spaces]*秒")
    private val quality = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")
    private val resolution = Regex("[1-9][0-9]{2,3}p")
    private val resolutionShape = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?[pPkK]")
    private val modelToken =
        Regex("([^〈〉]+?)(?:〈([a-z][a-z0-9_]{0,31})/([A-Za-z0-9][A-Za-z0-9_.:/-]{0,159})〉)?")
    private val fields = Regex("[0-9]+(?::[0-9]+|\\s*(?:张|秒|p|k))", RegexOption.IGNORE_CASE)

    private fun fail(code: String): Nothing = throw MediaHeaderException(code)

    private fun jsSpace(char: Char) = char in spaces

    private fun trimmed(text: String) =
        text.isNotEmpty() && !jsSpace(text.first()) && !jsSpace(text.last())

    private fun kind(text: String): MediaKind? =
        MediaKind.entries.firstOrNull { candidate ->
            val prefix = if (candidate == MediaKind.Image) "〔生成图像" else "〔生成视频"
            text.startsWith(prefix) &&
                (text.length == prefix.length ||
                    text[prefix.length].let { it == '〕' || it == '·' || jsSpace(it) })
        }

    private fun model(token: String): MediaModelRef {
        val match = modelToken.matchEntire(token) ?: fail("invalid_model")
        val label = match.groupValues[1]
        if (
            !trimmed(label) ||
                label.toByteArray().size > 160 ||
                label.any { it in "〔〕·" || it.code in 0..31 || it.code in 127..159 } ||
                fields.matches(label) ||
                label.startsWith("质量=")
        )
            fail("invalid_model")
        return MediaModelRef(
            null,
            match.groupValues[2].ifEmpty { null },
            match.groupValues[3].ifEmpty { null },
            label,
        )
    }

    fun markable(service: String, id: String) =
        service.matches(Regex("[a-z][a-z0-9_]{0,31}")) &&
            id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,159}"))

    fun expressible(label: String): Boolean =
        try {
            model(label).let {
                !it.pinned &&
                    read(format(MediaParameters(model = it), "."))?.parameters?.model?.label ==
                        label
            }
        } catch (_: MediaHeaderException) {
            false
        }

    fun read(text: String): MediaHeaderRead? {
        if (kind(text) == null) return null
        return try {
            val newline = text.indexOf('\n')
            if (newline < 0) fail("missing_prompt")
            val line = text.substring(0, newline).removeSuffix("\r")
            val prompt = text.substring(newline + 1)
            if (prompt.all(::jsSpace)) fail("missing_prompt")
            if (line.toByteArray().size > 1024 || prompt.toByteArray().size > 4096) fail("too_long")
            MediaHeaderRead(parseLine(line), prompt, null)
        } catch (error: MediaHeaderException) {
            MediaHeaderRead(null, null, error.code)
        }
    }

    private fun parseLine(line: String): MediaParameters {
        if (!line.endsWith('〕') || '\r' in line || '\n' in line) fail("invalid_header")
        val tokens = line.substring(1, line.length - 1).split(" · ")
        val kind =
            when (tokens.first()) {
                "生成图像" -> MediaKind.Image
                "生成视频" -> MediaKind.Video
                else -> fail("invalid_header")
            }
        if (tokens.size > 5 || tokens.any { !trimmed(it) || it.any { char -> char in "〔〕·" } })
            fail("invalid_header")
        var params = MediaParameters(kind, count = 1)
        var previous = 0
        for (token in tokens.drop(1)) {
            val rank =
                when {
                    aspectShape.matches(token) -> {
                        if (!aspect.matches(token)) fail("invalid_aspect")
                        params = params.copy(aspect = token)
                        1
                    }
                    countShape.matches(token) -> {
                        if (kind != MediaKind.Image || !token.matches(Regex("[1-4] 张")))
                            fail("invalid_count")
                        params = params.copy(count = token.substringBefore(' ').toInt())
                        2
                    }
                    secondsShape.matches(token) -> {
                        if (kind != MediaKind.Video || !token.matches(Regex("[1-9][0-9]? 秒")))
                            fail("invalid_seconds")
                        val seconds = token.substringBefore(' ').toInt()
                        if (seconds > 60) fail("invalid_seconds")
                        params = params.copy(seconds = seconds)
                        2
                    }
                    token.startsWith("质量=") -> {
                        val q = token.removePrefix("质量=")
                        if (kind != MediaKind.Image || !quality.matches(q)) fail("invalid_quality")
                        params = params.copy(quality = q)
                        3
                    }
                    resolutionShape.matches(token) -> {
                        if (kind != MediaKind.Video || !resolution.matches(token))
                            fail("invalid_quality")
                        params = params.copy(resolution = token)
                        3
                    }
                    else -> {
                        params = params.copy(model = model(token))
                        4
                    }
                }
            if (rank <= previous) fail("invalid_order")
            previous = rank
        }
        return params
    }

    fun format(params: MediaParameters, prompt: String): String {
        if (prompt.all(::jsSpace)) fail("missing_prompt")
        val parts = mutableListOf(if (params.kind == MediaKind.Image) "生成图像" else "生成视频")
        params.aspect?.let {
            if (!aspect.matches(it)) fail("invalid_aspect")
            parts.add(it)
        }
        if (params.kind == MediaKind.Image) {
            if (params.count !in 1..4) fail("invalid_count")
            parts.add("${params.count} 张")
            params.quality?.let {
                if (!quality.matches(it)) fail("invalid_quality")
                parts.add("质量=$it")
            }
        } else {
            params.seconds?.let {
                if (it !in 1..60) fail("invalid_seconds")
                parts.add("$it 秒")
            }
            params.resolution?.let {
                if (!resolution.matches(it)) fail("invalid_quality")
                parts.add(it)
            }
        }
        params.model?.let { value ->
            val alone = model(value.label)
            if (
                alone.pinned ||
                    alone.label != value.label ||
                    (value.service == null) != (value.id == null) ||
                    value.pinned && !markable(value.service!!, value.id!!)
            )
                fail("invalid_model")
            parts.add(value.label + if (value.pinned) "〈${value.service}/${value.id}〉" else "")
        }
        val message = "〔${parts.joinToString(" · ")}〕\n$prompt"
        val parsed = read(message) ?: fail("invalid_header")
        parsed.error?.let(::fail)
        if (parsed.parameters?.kind != params.kind || parsed.prompt != prompt)
            fail("invalid_header")
        return message
    }

    fun title(title: String): Pair<MediaKind, String>? {
        val kind = kind(title) ?: return null
        val prefix = if (kind == MediaKind.Image) "〔生成图像" else "〔生成视频"
        val after = title.substring(prefix.length)
        val end = title.indexOf('〕')
        if (after.isEmpty() || after == " ·" || after.startsWith(" · ") && end < 0)
            return kind to ""
        if (!after.startsWith(" · ") && !after.startsWith('〕')) return null
        return try {
            val line = title.substring(0, end + 1)
            if (line.toByteArray().size > 1024) return null
            parseLine(line)
            val rest = title.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(' ')) null else kind to rest.removePrefix(" ")
        } catch (_: MediaHeaderException) {
            null
        }
    }
}

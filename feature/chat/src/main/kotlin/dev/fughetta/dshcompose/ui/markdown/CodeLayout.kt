package dev.fughetta.dshcompose.ui.markdown

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*

/** Row ranges are immutable after construction and shared when colouring arrives. */
@Immutable
internal class CodeLayout
private constructor(
    val source: String,
    private val ranges: IntArray,
    val columns: Int,
    private val tokens: PersistentList<CodeToken>,
    private val logicalLines: IntArray,
) {
    val rowCount
        get() = ranges.size / 2

    fun text(row: Int): String = source.substring(ranges[row * 2], ranges[row * 2 + 1])

    fun logicalLine(row: Int): Int = logicalLines[row]

    fun continuation(row: Int): Boolean = ranges[row * 2].let { it > 0 && source[it - 1] != '\n' }

    fun coloured(tokens: PersistentList<CodeToken>) =
        CodeLayout(source, ranges, columns, tokens, logicalLines)

    fun tokens(row: Int): List<CodeToken> {
        val start = ranges[row * 2]
        val end = ranges[row * 2 + 1]
        var low = 0
        var high = tokens.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tokens[middle].end <= start) low = middle + 1 else high = middle
        }
        return buildList {
            var index = low
            while (index < tokens.size && tokens[index].start < end) {
                val token = tokens[index++]
                add(
                    token.copy(
                        start = maxOf(start, token.start) - start,
                        end = minOf(end, token.end) - start,
                    ),
                )
            }
        }
    }

    companion object {
        fun plain(source: String): CodeLayout {
            var rows = IntArray(128)
            var lines = IntArray(64)
            var line = 0
            var used = 0
            val length = source.length - if (source.endsWith('\n')) 1 else 0
            var start = 0
            var index = 0
            var columns = 0
            var widest = 1
            fun emit(end: Int) {
                if (used + 2 > rows.size) rows = rows.copyOf(rows.size * 2)
                val row = used / 2
                if (row >= lines.size) lines = lines.copyOf(lines.size * 2)
                lines[row] = line
                rows[used++] = start
                rows[used++] = if (end > start && source[end - 1] == '\r') end - 1 else end
                widest = maxOf(widest, columns)
            }
            while (index < length) {
                val char = source[index]
                if (char == '\n') {
                    emit(index)
                    line++
                    index++
                    start = index
                    columns = 0
                    continue
                }
                val pair =
                    char.isHighSurrogate() &&
                        index + 1 < length &&
                        source[index + 1].isLowSurrogate()
                var advance =
                    when {
                        char == '\t' -> 8 - columns % 8
                        char == '\r' -> 0
                        pair || char >= '\u2e80' -> 2
                        else -> 1
                    }
                // Minified output can contain millions of characters on one logical line. Split its
                // visual rows so Compose never receives a giant paragraph or an unrepresentable
                // width.
                if (columns + advance > 1024) {
                    emit(index)
                    start = index
                    columns = 0
                    if (char == '\t') advance = 8
                }
                columns += advance
                index += if (pair) 2 else 1
            }
            emit(length)
            return CodeLayout(
                source,
                rows.copyOf(used),
                widest,
                persistentListOf(),
                lines.copyOf(used / 2),
            )
        }
    }
}

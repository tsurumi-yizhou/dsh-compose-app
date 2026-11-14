package dev.fughetta.dshcompose.ui.transcript

import dev.fughetta.dshcompose.domain.transcript.TranscriptDiffLine

internal data class TranscriptDiffPart(
    val line: TranscriptDiffLine,
    val start: Int,
    val end: Int,
    val continuation: Boolean,
)

internal class TranscriptDiffLayout(lines: List<TranscriptDiffLine>) {
    val parts = ArrayList<TranscriptDiffPart>()
    var columns = 1
        private set

    val digits =
        lines.maxOfOrNull { maxOf(it.oldLine ?: 0, it.newLine ?: 0) }?.toString()?.length ?: 1

    init {
        for (line in lines) {
            var at = 0
            var start = 0
            var width = 0
            while (at < line.text.length) {
                val char = line.text[at]
                val pair =
                    char.isHighSurrogate() && line.text.getOrNull(at + 1)?.isLowSurrogate() == true
                var advance =
                    when {
                        char == '\t' -> 8 - width % 8
                        char == '\r' -> 0
                        pair || char >= '\u2e80' -> 2
                        else -> 1
                    }
                if (width + advance > 1024) {
                    parts += TranscriptDiffPart(line, start, at, start > 0)
                    columns = maxOf(columns, width)
                    start = at
                    width = 0
                    if (char == '\t') advance = 8
                }
                width += advance
                at += if (pair) 2 else 1
            }
            parts += TranscriptDiffPart(line, start, at, start > 0)
            columns = maxOf(columns, width)
        }
    }
}

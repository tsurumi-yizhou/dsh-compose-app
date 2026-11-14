package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration

internal data class TranscriptSgr(
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

/**
 * Indexes line boundaries and inherited SGR state. Only composed lines produce text/style nodes.
 */
internal class TranscriptAnsiLayout(private val source: String) {
    private val starts = ArrayList<Int>()
    private val states = ArrayList<TranscriptSgr>()
    private val ends = ArrayList<Int>()
    var columns = 1
        private set

    private val csi = Regex("\u001B\\[([0-9;?]*)([A-Za-z])")
    val size
        get() = starts.size

    init {
        starts += 0
        var style = TranscriptSgr()
        states += style
        var at = 0
        var width = 0
        while (at < source.length) {
            when (source[at]) {
                '\n' -> {
                    columns = maxOf(columns, width)
                    ends += at
                    starts += at + 1
                    states += style
                    width = 0
                    at++
                }
                '\u001B' -> {
                    val end = oscEnd(at)
                    if (end != null) {
                        at = end
                        continue
                    }
                    val match = csi.matchAt(source, at)
                    if (match != null) {
                        if (match.groupValues[2] == "m") style = change(style, match.groupValues[1])
                        at = match.range.last + 1
                    } else at++
                }
                else -> {
                    val char = source[at]
                    val pair =
                        char.isHighSurrogate() && source.getOrNull(at + 1)?.isLowSurrogate() == true
                    var advance =
                        when {
                            char == '\t' -> 8 - width % 8
                            char == '\r' -> 0
                            pair || char >= '\u2e80' -> 2
                            else -> 1
                        }
                    if (width + advance > 1024) {
                        columns = maxOf(columns, width)
                        ends += at
                        starts += at
                        states += style
                        width = 0
                        if (char == '\t') advance = 8
                    }
                    width += advance
                    at += if (pair) 2 else 1
                }
            }
        }
        columns = maxOf(columns, width)
        ends += source.length
    }

    fun line(index: Int, palette: List<Color>): AnnotatedString {
        val start = starts[index]
        val end = ends[index]
        var at = start
        var plain = start
        var state = states[index]
        return buildAnnotatedString {
            fun flush(to: Int) {
                if (to <= plain) return
                val from = length
                append(source.substring(plain, to))
                addStyle(state.style(palette), from, length)
            }
            while (at < end) {
                if (source[at] == '\u001B') {
                    val osc = oscEnd(at)
                    val match = csi.matchAt(source, at)
                    if (osc != null || match != null) {
                        flush(at)
                        if (match != null && match.groupValues[2] == "m")
                            state = change(state, match.groupValues[1])
                        at = (osc ?: match!!.range.last + 1).coerceAtMost(end)
                        plain = at
                        continue
                    }
                }
                at++
            }
            flush(end)
        }
    }

    private fun oscEnd(start: Int): Int? {
        if (source.getOrNull(start + 1) != ']') return null
        val bel = source.indexOf('\u0007', start + 2)
        val st = source.indexOf("\u001B\\", start + 2)
        val end =
            listOf(bel.takeIf { it >= 0 }?.plus(1), st.takeIf { it >= 0 }?.plus(2))
                .filterNotNull()
                .minOrNull()
        return end ?: source.length
    }

    private fun change(old: TranscriptSgr, parameters: String): TranscriptSgr {
        var state = old
        val values = parameters.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < values.size) {
            when (val code = values[i]) {
                0 -> state = TranscriptSgr()
                1 -> state = state.copy(bold = true)
                3 -> state = state.copy(italic = true)
                4 -> state = state.copy(underline = true)
                22 -> state = state.copy(bold = false)
                23 -> state = state.copy(italic = false)
                24 -> state = state.copy(underline = false)
                39 -> state = state.copy(fg = null)
                49 -> state = state.copy(bg = null)
                in 30..37 -> state = state.copy(fg = code - 30)
                in 90..97 -> state = state.copy(fg = code - 82)
                in 40..47 -> state = state.copy(bg = code - 40)
                in 100..107 -> state = state.copy(bg = code - 92)
                38,
                48 -> {
                    var value: Int? = null
                    when (values.getOrNull(i + 1)) {
                        5 -> {
                            value = values.getOrNull(i + 2)?.let(::xterm)
                            i += 2
                        }
                        2 -> {
                            if (i + 4 < values.size) {
                                value = rgb(values[i + 2], values[i + 3], values[i + 4])
                                i += 4
                            }
                        }
                    }
                    if (value != null)
                        state = if (code == 38) state.copy(fg = value) else state.copy(bg = value)
                }
            }
            i++
        }
        return state
    }

    private fun TranscriptSgr.style(palette: List<Color>): SpanStyle {
        fun color(raw: Int?) = raw?.let { if (it in 0..15) palette[it] else Color(it) }
        return SpanStyle(
            color = color(fg) ?: Color.Unspecified,
            background = color(bg)?.copy(alpha = .18f) ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.SemiBold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

    private fun rgb(r: Int, g: Int, b: Int) =
        0xFF000000.toInt() or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    private fun xterm(value: Int): Int? {
        if (value !in 0..255) return null
        if (value < 16) return value
        if (value >= 232) {
            val gray = 8 + (value - 232) * 10
            return rgb(gray, gray, gray)
        }
        val n = value - 16
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        return rgb(steps[n / 36], steps[n % 36 / 6], steps[n % 6])
    }
}

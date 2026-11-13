package dev.fughetta.dshcompose.data.jobs

import kotlinx.collections.immutable.*

/**
 * Single collector owns this buffer. Escape sequences are consumed incrementally without retaining
 * their payload.
 */
internal class JobOutputBuffer {
    private var complete = persistentListOf<String>()
    private val partial = StringBuilder()
    var firstLine = 0L
        private set

    private var escape = 0
    private var carriage = false
    var trimmed = false
        private set

    fun markGap() {
        trimmed = true
        escape = 0
        carriage = false
        partial.setLength(0)
    }

    fun add(text: String) {
        for (char in text) {
            when (escape) {
                1 -> {
                    escape =
                        when (char) {
                            '[' -> 2
                            ']' -> 3
                            else -> 0
                        }
                    continue
                }
                2 -> {
                    if (char in '@'..'~') escape = 0
                    continue
                }
                3 -> {
                    if (char == '\u0007') escape = 0 else if (char == '\u001b') escape = 4
                    continue
                }
                4 -> {
                    escape =
                        if (char == '\\' || char == '\u0007') 0 else if (char == '\u001b') 4 else 3
                    continue
                }
            }
            if (char == '\u001b') {
                escape = 1
                continue
            }
            if (char == '\n') {
                complete = complete.adding(line())
                partial.setLength(0)
                carriage = false
                trim()
                continue
            }
            if (char == '\r') {
                carriage = true
                continue
            }
            if (carriage) {
                partial.setLength(0)
                carriage = false
            }
            if (char < ' ' && char != '\t') continue
            if (partial.length < MAX_LINE) partial.append(char) else trimmed = true
            trim()
        }
    }

    private fun line(): String {
        val size = partial.length
        return if (size > 0 && partial[size - 1].isHighSurrogate()) partial.substring(0, size - 1)
        else partial.toString()
    }

    private fun trim() {
        val limit = MAX_LINES - if (partial.isEmpty()) 0 else 1
        while (complete.size > limit) {
            complete = complete.removingAt(0)
            firstLine++
            trimmed = true
        }
    }

    fun lines(): PersistentList<String> =
        if (partial.isEmpty()) complete else complete.adding(line())

    companion object {
        const val MAX_LINES = 1000
        const val MAX_LINE = 4000
    }
}

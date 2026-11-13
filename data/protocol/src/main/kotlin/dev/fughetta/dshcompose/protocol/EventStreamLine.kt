package dev.fughetta.dshcompose.protocol

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLineStrictTo
import java.io.EOFException

/** Bounded UTF-8 event lines, including a final line without a delimiter. */
internal suspend fun ByteReadChannel.readEventLine(): String? {
    val text = StringBuilder()
    val available =
        try {
            readLineStrictTo(text, limit = 1024 * 1024L, lineEnding = LineEnding.Lenient) >= 0
        } catch (_: EOFException) {
            true
        }
    return if (available) text.toString() else null
}

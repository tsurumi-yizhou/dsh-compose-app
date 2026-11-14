package dev.fughetta.dshcompose.diagnostics

import java.io.File
import java.io.RandomAccessFile

object Diagnostics {
    fun tail(file: File, maxBytes: Int): List<String> {
        if (!file.isFile) return emptyList()
        return RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0)
            val bytes = ByteArray((length - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).split('\n')
            // A cut first line is half a line: drop it.
            val whole = if (start > 0) lines.drop(1) else lines
            whole.dropLastWhile { it.isEmpty() }
        }
    }
}

package dev.fughetta.dshcompose.engine.runtime

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RuntimeFiles {
    fun formatTime(at: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(at))

    fun stamp(at: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(at))

    fun isTrivialPatch(text: String): Boolean {
        val body =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString("")
        return body.isEmpty() || body == "[]"
    }

    fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.dsh-app-${System.nanoTime()}.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.path}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

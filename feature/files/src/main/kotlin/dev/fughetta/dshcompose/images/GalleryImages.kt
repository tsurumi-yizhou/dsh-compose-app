package dev.fughetta.dshcompose.images

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal object GalleryImages {
    fun name(given: String, mime: String): String {
        val filename = given.substringAfterLast('/').substringAfterLast('\\')
        var stem =
            filename
                .substringBeforeLast('.', filename)
                .replace(Regex("[\\x00-\\x1f\\\\:*?\"<>|]"), "_")
                .trim()
        if (stem.isBlank() || stem.startsWith('.'))
            stem = "DSH_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        if (stem.length > 80)
            stem =
                stem
                    .substring(
                        0,
                        if (stem[79].isHighSurrogate() && stem[80].isLowSurrogate()) 79 else 80,
                    )
                    .trim()
        val extension =
            when (mime) {
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "png"
            }
        return "$stem.$extension"
    }

    /**
     * A bounded, private cache; chooser results cannot tell us when a receiver finishes reading.
     */
    fun shareCopy(source: StagedImage, root: File, now: Long = System.currentTimeMillis()): File {
        root.mkdirs()
        val entries =
            root
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.lastModified() }
                .toMutableList()
        var bytes = entries.sumOf { entry -> entry.listFiles().orEmpty().sumOf { it.length() } }
        for (entry in entries) {
            if (
                now - entry.lastModified() > 24 * 60 * 60 * 1000L ||
                    bytes + source.file.length() > 100L * 1024 * 1024
            ) {
                val size = entry.listFiles().orEmpty().sumOf { it.length() }
                if (entry.deleteRecursively()) bytes -= size
            }
        }
        val directory = File(root, UUID.randomUUID().toString()).also { it.mkdirs() }
        try {
            return source.file.copyTo(File(directory, source.name))
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }
}

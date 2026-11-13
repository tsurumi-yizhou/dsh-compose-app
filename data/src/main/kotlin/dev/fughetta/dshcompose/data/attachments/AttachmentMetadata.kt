package dev.fughetta.dshcompose.data.attachments

import android.webkit.MimeTypeMap
import java.io.File

object AttachmentMetadata {
    fun mimeOf(given: String?, name: String): String {
        if (!given.isNullOrBlank() && given != "application/octet-stream") return given
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun imageMime(file: File): String? {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        fun starts(vararg bytes: Int) =
            count >= bytes.size && bytes.indices.all { header[it].toInt() and 255 == bytes[it] }
        return when {
            starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            starts(255, 216, 255) -> "image/jpeg"
            starts(71, 73, 70, 56, 55, 97) || starts(71, 73, 70, 56, 57, 97) -> "image/gif"
            starts(82, 73, 70, 70) &&
                count >= 12 &&
                header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
            else -> null
        }
    }
}

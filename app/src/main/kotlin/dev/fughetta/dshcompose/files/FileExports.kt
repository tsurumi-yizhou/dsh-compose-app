package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.domain.attachments.attachmentFileName
import java.io.File
import java.security.MessageDigest

object FileExports {
    internal fun copyForOpen(file: File, root: File, name: String): File {
        val source = file.canonicalFile
        val key =
            MessageDigest.getInstance("SHA-256")
                .digest(source.path.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val target = File(File(root, key), attachmentFileName(name))
        return source.copyTo(target, overwrite = true)
    }

    fun unique(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$stem ($n)$ext").also { n++ }
        return candidate
    }
}

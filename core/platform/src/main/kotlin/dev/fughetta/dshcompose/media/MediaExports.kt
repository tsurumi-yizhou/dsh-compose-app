package dev.fughetta.dshcompose.media

import dev.fughetta.dshcompose.files.FileExports
import java.io.File
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

object MediaExports {
    fun writeUnique(dir: File, name: String, bytes: ByteArray): File =
        writeUnique(dir, name) { it.write(bytes) }

    fun writeUnique(dir: File, name: String, write: (OutputStream) -> Unit): File {
        while (true) {
            val target = FileExports.unique(dir, name)
            val output =
                try {
                    Files.newOutputStream(
                        target.toPath(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
            try {
                output.use(write)
                return target
            } catch (error: Throwable) {
                try {
                    Files.deleteIfExists(target.toPath())
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }
}

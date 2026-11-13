package dev.fughetta.dshcompose.data.jobs

import java.io.File
import java.io.OutputStream

/** Private share cache and user-selected document access. */
internal class JobOutputFilesDataSource(
    private val directory: File,
    private val openDocument: (String) -> OutputStream,
) {
    fun share(lines: List<String>): File {
        if (!directory.isDirectory && !directory.mkdirs())
            throw java.io.IOException("jobs/share-failed")
        directory
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(7)
            ?.forEach { it.delete() }
        val target = File.createTempFile("task-", ".txt", directory)
        try {
            target.outputStream().writeLines(lines)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun save(destination: String, lines: List<String>) {
        openDocument(destination).writeLines(lines)
    }

    private fun OutputStream.writeLines(lines: List<String>) {
        bufferedWriter().use { writer ->
            lines.forEachIndexed { index, line ->
                if (index > 0) writer.newLine()
                writer.write(line)
            }
        }
    }
}

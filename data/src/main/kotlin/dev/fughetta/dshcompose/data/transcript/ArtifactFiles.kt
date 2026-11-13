package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.attachments.attachmentFileName
import dev.fughetta.dshcompose.domain.transcript.ArtifactTable
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** A temporary download lives until the consumer saves it or transfers it to an Android handler. */
suspend fun withArtifactFile(
    owner: WorkspaceFilesRepository,
    session: String,
    path: String,
    name: String,
    cache: File,
    existing: File?,
    progress: suspend (Long, Long?) -> Unit,
    consume: suspend (File) -> Boolean,
) {
    var downloaded: File? = null
    var handedOff = false
    try {
        val source =
            existing
                ?: withContext(Dispatchers.IO) {
                    val directory = File(cache, "artifacts").apply { mkdirs() }
                    directory
                        .listFiles()
                        ?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }
                        ?.forEach { it.deleteRecursively() }
                    val folder = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
                    val output = File(folder, attachmentFileName(name))
                    downloaded = output
                    output.outputStream().use { stream ->
                        owner.copy(
                            session,
                            path,
                            stream::write,
                            300L * 1024 * 1024,
                            onProgress = progress,
                        )
                    }
                    output
                }
        handedOff = consume(source)
    } finally {
        if (!handedOff)
            withContext(NonCancellable + Dispatchers.IO) {
                downloaded?.let {
                    it.delete()
                    it.parentFile?.delete()
                }
            }
    }
}

suspend fun <T> readArtifactTable(
    owner: WorkspaceFilesRepository,
    session: String,
    path: String,
    cache: File,
    delimited: (ByteArray, Boolean, Boolean) -> T,
    workbook: (File) -> T,
): ArtifactTable<T> {
    var temporary: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val ext = path.substringAfterLast('.').lowercase()
            if (ext in setOf("csv", "tsv")) {
                val window = owner.readBytes(session, path, 0, 2 * 1024 * 1024)
                ArtifactTable(delimited(window.data, ext == "tsv", !window.eof), window.version)
            } else {
                val folder = File(cache, "artifacts").apply { mkdirs() }
                val file = File.createTempFile("workbook-", ".xlsx", folder).also { temporary = it }
                val version =
                    file.outputStream().use {
                        owner.copy(session, path, it::write, 16L * 1024 * 1024).version
                    }
                ArtifactTable(workbook(file), version)
            }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
    }
}

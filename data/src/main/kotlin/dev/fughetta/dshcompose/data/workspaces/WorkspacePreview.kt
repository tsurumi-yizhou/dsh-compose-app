package dev.fughetta.dshcompose.data.workspaces

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.workspaces.*
import java.io.File
import java.io.IOException
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

suspend fun loadWorkspacePreview(
    directory: File?,
    client: WorkspaceFilesRepository,
    session: String,
    path: String,
    renderPdf: Boolean,
    maximumBytes: Long,
): CachedWorkspacePreview {
    var file: File? = null
    try {
        return withContext(Dispatchers.IO) {
            val root = directory ?: throw IOException("No preview directory")
            root.mkdirs()
            val target = File.createTempFile("preview-", ".preview", root)
            file = target
            var fonts = persistentListOf<String>()
            val receipt =
                target.outputStream().use { output ->
                    if (renderPdf) {
                        val before = client.stat(session, path)
                        val converted = client.renderPdf(session, path, output::write)
                        val after = client.stat(session, path)
                        if (before.version != after.version)
                            throw OperationFailure("workspace-file/changed")
                        fonts = converted.missingFonts
                        WorkspaceFileCopy(converted.bytes, after.version)
                    } else client.copy(session, path, output::write, maximumBytes)
                }
            CachedWorkspacePreview(target.absolutePath, receipt, fonts)
        }
    } catch (error: Throwable) {
        file?.delete()
        throw error
    }
}

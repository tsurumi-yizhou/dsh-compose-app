package dev.fughetta.dshcompose.files

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.fughetta.dshcompose.ui.*
import java.io.File
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Archive bytes stream straight to one pending destination; publication never needs a second copy.
 */
class NativeSessionArchives(private val context: Context) {
    suspend fun create(operation: SessionExportOperation, name: String): SessionArchiveDestination {
        currentCoroutineContext().ensureActive()
        return try {
            if (operation == SessionExportOperation.Share) {
                val root = File(context.cacheDir, "artifacts/session-exports").apply { mkdirs() }
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                root
                    .listFiles()
                    .orEmpty()
                    .filter { it.lastModified() < cutoff }
                    .forEach { it.deleteRecursively() }
                val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                val file = File(directory, name)
                val output =
                    try {
                        file.outputStream()
                    } catch (error: Exception) {
                        directory.deleteRecursively()
                        throw error
                    }
                fileDestination(
                    file,
                    output,
                    directory,
                    SessionArchiveResult(share = SessionArchiveShare(file.path, name)),
                )
            } else if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val relative = "${Environment.DIRECTORY_DOWNLOADS}/DSH Compose"
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val uri =
                    resolver.insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values,
                    ) ?: throw java.io.IOException("Archive destination unavailable")
                val output =
                    try {
                        resolver.openOutputStream(uri, "w")
                            ?: throw java.io.IOException("Archive destination unavailable")
                    } catch (error: Exception) {
                        try {
                            resolver.delete(uri, null, null)
                        } catch (cleanup: Exception) {
                            error.addSuppressed(cleanup)
                        }
                        throw error
                    }
                SessionArchiveDestination(
                    output = output,
                    publish = {
                        if (
                            resolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                                },
                                null,
                                null,
                            ) != 1
                        )
                            throw java.io.IOException("Archive publication failed")
                        SessionArchiveResult(savedTo = "$relative/$name")
                    },
                    finish = { committed -> if (!committed) resolver.delete(uri, null, null) },
                )
            } else {
                val directory =
                    File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS,
                            ),
                            "DSH Compose",
                        )
                        .apply { mkdirs() }
                var file: File
                var output: OutputStream
                while (true) {
                    file = FileExports.unique(directory, name)
                    try {
                        output =
                            Files.newOutputStream(
                                file.toPath(),
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                            )
                        break
                    } catch (_: FileAlreadyExistsException) {}
                }
                fileDestination(file, output, null, SessionArchiveResult(savedTo = file.path)) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.path),
                        arrayOf("application/zip"),
                        null,
                    )
                }
            }
        } catch (_: SecurityException) {
            throw SessionArchiveException("permission")
        }
    }
}

private fun fileDestination(
    file: File,
    output: OutputStream,
    directory: File?,
    result: SessionArchiveResult,
    onPublished: () -> Unit = {},
): SessionArchiveDestination =
    SessionArchiveDestination(
        output = output,
        publish = {
            onPublished()
            result
        },
        finish = { committed ->
            if (!committed) {
                if (directory != null) directory.deleteRecursively() else file.delete()
            }
        },
    )

package dev.fughetta.dshcompose.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.fughetta.dshcompose.domain.OperationFailure
import java.io.File
import kotlinx.coroutines.*

internal suspend fun saveGeneratedVideo(context: Context, file: File, mime: String) {
    val transfer = currentCoroutineContext()
    fun copy(target: java.io.OutputStream) {
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                transfer.ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                target.write(buffer, 0, read)
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/DSH Compose")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val resolver = context.contentResolver
        val uri =
            resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw OperationFailure("media/save-failed")
        try {
            resolver.openOutputStream(uri)?.use(::copy)
                ?: throw OperationFailure("media/save-failed")
            transfer.ensureActive()
            if (
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) != 1
            )
                throw OperationFailure("media/save-failed")
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    } else {
        val directory =
            File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "DSH Compose",
                )
                .also { it.mkdirs() }
        val saved = MediaExports.writeUnique(directory, file.name, ::copy)
        MediaScannerConnection.scanFile(context, arrayOf(saved.path), arrayOf(mime), null)
    }
}

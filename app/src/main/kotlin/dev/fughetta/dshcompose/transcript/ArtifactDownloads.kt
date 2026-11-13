package dev.fughetta.dshcompose.transcript

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.fughetta.dshcompose.domain.attachments.attachmentFileName
import dev.fughetta.dshcompose.files.LegacyDownloads
import java.io.File

internal fun saveArtifact(app: Context, source: File, name: String, mime: String) {
    if (Build.VERSION.SDK_INT >= 29) {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values =
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    attachmentFileName(name),
                )
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DSH-Compose")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = app.contentResolver.insert(collection, values) ?: error("artifact/save-failed")
        try {
            app.contentResolver.openOutputStream(uri, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out, 32768) }
            } ?: error("artifact/save-failed")
            app.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Throwable) {
            app.contentResolver.delete(uri, null, null)
            throw error
        }
    } else {
        val directory =
            File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "DSH-Compose",
                )
                .apply { mkdirs() }
        source.inputStream().use {
            LegacyDownloads.copy(
                it,
                directory,
                attachmentFileName(name),
            )
        }
    }
}

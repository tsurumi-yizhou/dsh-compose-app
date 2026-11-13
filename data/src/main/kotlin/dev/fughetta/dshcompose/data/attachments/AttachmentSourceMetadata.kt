package dev.fughetta.dshcompose.data.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.fughetta.dshcompose.domain.attachments.AttachmentSource

internal data class AttachmentSourceMetadata(val name: String, val mediaType: String?)

internal fun attachmentSourceMetadata(
    resolver: ContentResolver,
    source: AttachmentSource,
): AttachmentSourceMetadata {
    val uri = Uri.parse(source.uri)
    val name =
        source.displayName
            ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?: "file"
    return AttachmentSourceMetadata(
        name,
        source.mediaType ?: resolver.getType(uri),
    )
}

package dev.fughetta.dshcompose.domain.attachments

import dev.fughetta.dshcompose.domain.messages.*

class AttachmentImportException(val code: String) : Exception(code)

data class ComposerFile(
    val id: String,
    val name: String,
    val mediaType: String,
    val path: String,
    val bytes: Long,
) {
    val isImage
        get() = mediaType in AttachmentLimits.imageTypes
}

data class AttachmentSource(
    val uri: String,
    val displayName: String? = null,
    val mediaType: String? = null,
)

object AttachmentLimits {
    val imageTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    const val MAX_FILES = 9
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    const val MAX_FILE_BYTES = 200L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
}

/** Reads, upload receipts and prepared submissions owned by one connection. */
interface AttachmentsRepository : AutoCloseable {
    suspend fun importImage(bytes: ByteArray, name: String?, mediaType: String?): ComposerFile

    suspend fun importFile(source: AttachmentSource, availableBytes: Long): ComposerFile

    /** Completes cleanup even when the importing caller has been cancelled. */
    suspend fun release(files: List<ComposerFile>)

    suspend fun readImage(sessionId: String, reference: AttachmentReference): ByteArray

    suspend fun prepare(
        sessionId: String,
        files: List<ComposerFile>,
        onProgress: suspend (AttachmentUploadProgress) -> Unit = {},
    ): PreparedMessageAttachments

    /** Rebuild inline images while preserving the original request and receipt identifiers. */
    suspend fun prepareRetry(pending: PendingPrompt): PendingPromptSubmission

    fun forget(attachmentIds: Set<String>)

    fun retain(attachmentIds: Set<String>)

    fun forgetSession(sessionId: String)
}

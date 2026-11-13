package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.attachments.*

/** Returns owned composer files; partial failure releases every copy created by this request. */
class RestorePromptImagesUseCase(
    private val images: AttachmentsRepository,
    private val importImage: suspend (ByteArray, String?, String?) -> ComposerFile,
    private val release: suspend (List<ComposerFile>) -> Unit,
) {
    suspend operator fun invoke(
        sessionId: String,
        attachments: List<ChatAttachment>,
    ): List<ComposerFile> {
        if (attachments.size > AttachmentLimits.MAX_FILES)
            throw AttachmentImportException("too_many")
        if (attachments.any { it.kind != "image" })
            throw AttachmentImportException("unsupported_image")
        val restored = mutableListOf<ComposerFile>()
        try {
            for (attachment in attachments) {
                val bytes = images.readImage(sessionId, attachment.reference)
                restored += importImage(bytes, attachment.name, attachment.mediaType)
            }
            return restored.toList()
        } catch (error: Throwable) {
            release(restored)
            throw error
        }
    }
}

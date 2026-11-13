package dev.fughetta.dshcompose.domain.attachments

/** The same selection budget applies to imports, restored prompts and generated media. */
class ValidateAttachmentSelectionUseCase {
    operator fun invoke(existing: List<ComposerFile>, incoming: List<ComposerFile>) {
        if (existing.size + incoming.size > AttachmentLimits.MAX_FILES)
            throw AttachmentImportException("too_many")
        if (
            existing.sumOf { it.bytes } + incoming.sumOf { it.bytes } >
                AttachmentLimits.MAX_TOTAL_BYTES
        )
            throw AttachmentImportException("too_large")
    }
}

package dev.fughetta.dshcompose.domain.messages

data class AttachmentUploadProgress(
    val attachmentId: String,
    val bytes: Long,
    val totalBytes: Long?,
)

data class PreparedMessageAttachments(
    val fileReceiptIds: List<String>,
    val workspacePaths: List<String>,
    val submission: PendingPromptSubmission,
)

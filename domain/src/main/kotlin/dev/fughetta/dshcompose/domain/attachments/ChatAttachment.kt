package dev.fughetta.dshcompose.domain.attachments

/**
 * A reference issued by a data source. [payload] is the issuer's complete representation; the
 * domain never interprets it.
 */
data class AttachmentReference(val payload: Any? = null)

data class ChatAttachment(
    val kind: String,
    val attachmentId: String?,
    val name: String?,
    val mediaType: String?,
    val bytes: Long?,
    val reference: AttachmentReference,
)

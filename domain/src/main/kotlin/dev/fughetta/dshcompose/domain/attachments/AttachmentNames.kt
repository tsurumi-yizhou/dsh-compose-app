package dev.fughetta.dshcompose.domain.attachments

fun attachmentFileName(name: String): String {
    val cleaned =
        name.substringAfterLast('/').replace(Regex("[\\u0000-\\u001f\\\\:*?\"<>|]"), "_").trim()
    return cleaned.ifEmpty { "file" }
}

package dev.fughetta.dshcompose.domain.sessions

import kotlinx.collections.immutable.PersistentList

enum class TranscriptArtifactKind {
    Image,
    Pdf,
    Document,
    Spreadsheet,
    Slides,
    Archive,
    Audio,
    Video,
    Web,
    Markdown,
    Text,
    Other,
}

enum class TranscriptArtifactOrigin {
    Delivered,
    Written,
    Linked,
    PhoneOutput,
}

data class TranscriptArtifact(
    val path: String,
    val origin: TranscriptArtifactOrigin,
    val turnId: String,
    val description: String? = null,
    val seq: Long? = null,
    val bytes: Long? = null,
) {
    val name
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    val kind
        get() = transcriptArtifactKind(path)
}

data class TranscriptFileChange(
    val path: String,
    val callIds: PersistentList<String>,
    val blocks: PersistentList<TranscriptChangeBlock>,
)

fun transcriptArtifactKind(path: String): TranscriptArtifactKind {
    val ext = path.substringAfterLast('.').lowercase()
    return when (ext) {
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "bmp",
        "heic",
        "heif",
        "avif",
        "tif",
        "tiff",
        "ico",
        "svg" -> TranscriptArtifactKind.Image
        "pdf" -> TranscriptArtifactKind.Pdf
        "doc",
        "docx",
        "odt",
        "rtf",
        "pages",
        "wps",
        "epub" -> TranscriptArtifactKind.Document
        "xls",
        "xlsx",
        "xlsm",
        "ods",
        "csv",
        "tsv",
        "numbers",
        "et" -> TranscriptArtifactKind.Spreadsheet
        "ppt",
        "pptx",
        "odp",
        "key",
        "dps" -> TranscriptArtifactKind.Slides
        "zip",
        "tar",
        "gz",
        "tgz",
        "bz2",
        "xz",
        "7z",
        "rar",
        "zst",
        "apk" -> TranscriptArtifactKind.Archive
        "mp3",
        "wav",
        "m4a",
        "aac",
        "flac",
        "ogg",
        "opus",
        "amr",
        "mid",
        "midi" -> TranscriptArtifactKind.Audio
        "mp4",
        "mov",
        "webm",
        "mkv",
        "avi",
        "m4v",
        "3gp" -> TranscriptArtifactKind.Video
        "html",
        "htm",
        "mhtml" -> TranscriptArtifactKind.Web
        "md",
        "markdown" -> TranscriptArtifactKind.Markdown
        "txt",
        "log" -> TranscriptArtifactKind.Text
        else -> TranscriptArtifactKind.Other
    }
}

data class TranscriptChangeBlock(
    val old: String?,
    val new: String,
    val oldStart: Int? = null,
    val newStart: Int? = null,
)

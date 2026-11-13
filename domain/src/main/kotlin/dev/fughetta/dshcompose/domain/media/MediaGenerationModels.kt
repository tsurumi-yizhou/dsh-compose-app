package dev.fughetta.dshcompose.domain.media

enum class MediaKind {
    Image,
    Video;

    val wire
        get() = name.lowercase()
}

enum class MediaStatus {
    Running,
    Done,
    Failed,
}

data class GeneratedMediaItem(
    val path: String,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val bytes: Long?,
    val duration: Double?,
    val poster: String?,
    val codec: String?,
) {
    val name
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    val video
        get() = mime?.startsWith("video/") == true
}

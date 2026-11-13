package dev.fughetta.dshcompose.domain.media

import dev.fughetta.dshcompose.domain.chat.ToolPayload

data class MediaGenerationRequest(
    val label: String?,
    val model: String?,
    val provider: String?,
    val aspect: String?,
    val count: Int?,
    val quality: String?,
    val seconds: Double?,
    val secondsLabel: String?,
    val resolution: String?,
)

data class MediaCost(val estimate: Double?, val currency: String?)

data class MediaProvenance(val visibleMark: Boolean, val c2pa: Boolean, val implicitLabel: Boolean)

data class MediaGenerationError(val text: String?, val code: String?)

data class GeneratedMedia(
    val rid: String?,
    val kind: MediaKind,
    val status: MediaStatus,
    val job: String?,
    val items: List<GeneratedMediaItem>,
    val request: MediaGenerationRequest,
    val prompt: String?,
    val revisedPrompt: String?,
    val cost: MediaCost?,
    val provenance: MediaProvenance?,
    val error: MediaGenerationError?,
    val raw: ToolPayload.Obj,
) {
    val paths
        get() = items.flatMap { listOfNotNull(it.path, it.poster) }.toSet()

    val copyPrompt
        get() = revisedPrompt ?: prompt
}

data class MediaTranscriptItem(
    val key: String,
    val toolCallId: String,
    val createdAt: Long,
    val seq: Long,
    val media: GeneratedMedia,
)

enum class MediaCatalogStatus {
    Loading,
    Missing,
    Ready,
    Invalid,
}

data class MediaWork(
    val key: String,
    val sessionId: String,
    val createdAt: Long,
    val cwd: String?,
    val media: GeneratedMedia,
    val local: Boolean = false,
)

data class MediaWorksPage(val items: List<MediaWork>, val next: String?)

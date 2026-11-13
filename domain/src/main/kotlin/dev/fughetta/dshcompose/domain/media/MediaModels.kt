package dev.fughetta.dshcompose.domain.media

/** A model chosen for a generation request. Wire form is built by the protocol layer. */
data class MediaModelRef(
    val namespace: String?,
    val service: String?,
    val id: String?,
    val label: String,
) {
    val pinned
        get() = service != null && id != null
}

/** Everything a generation request needs to be sent. */
data class MediaParameters(
    val kind: MediaKind = MediaKind.Image,
    val model: MediaModelRef? = null,
    val aspect: String? = null,
    val count: Int = 1,
    val quality: String? = null,
    val seconds: Int? = null,
    val resolution: String? = null,
)

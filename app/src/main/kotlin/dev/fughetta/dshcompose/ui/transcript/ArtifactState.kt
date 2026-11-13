package dev.fughetta.dshcompose.ui.transcript

import kotlinx.collections.immutable.*

data class TranscriptSheet(
    val name: String,
    val rows: PersistentList<PersistentList<String>>,
    val columns: Int,
    val truncated: Boolean = false,
)

data class TranscriptSheetPreview(
    val sessionId: String,
    val path: String,
    val sheets: PersistentList<TranscriptSheet> = persistentListOf(),
    val selected: Int = 0,
    val loading: Boolean = true,
    val changed: Boolean = false,
    val version: String? = null,
    val error: String? = null,
)

data class TranscriptArtifactState(
    val busy: Boolean = false,
    val bytes: Long = 0,
    val total: Long? = null,
    val error: String? = null,
    val notice: String? = null,
    val sheet: TranscriptSheetPreview? = null,
)

sealed interface TranscriptArtifactEffect {
    data class Preview(val sessionId: String, val path: String) : TranscriptArtifactEffect

    data class Open(val path: String, val mime: String, val chooser: Boolean) :
        TranscriptArtifactEffect

    data class Share(val path: String, val mime: String) : TranscriptArtifactEffect

    data class CopyPath(val path: String) : TranscriptArtifactEffect

    data object StoragePermission : TranscriptArtifactEffect
}

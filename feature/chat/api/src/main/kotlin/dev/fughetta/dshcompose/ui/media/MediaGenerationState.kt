package dev.fughetta.dshcompose.ui.media

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.GeneratedMediaItem
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.domain.media.MediaParameters
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.ImageExportOperation
import kotlinx.collections.immutable.*

data class MediaWorksState(
    val open: Boolean = false,
    val items: PersistentList<MediaWork> = persistentListOf(),
    val next: String? = null,
    val loading: Boolean = false,
    val filter: MediaKind? = null,
    val error: String? = null,
    val local: Boolean = false,
    val hasPrevious: Boolean = false,
)

data class MediaPreviewState(
    val works: PersistentList<MediaWork>,
    val workIndex: Int,
    val itemIndex: Int = 0,
    val videoPath: String? = null,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

data class MediaGenerationState(
    val catalog: MediaCatalog = MediaCatalog(),
    val modes: PersistentMap<String, MediaParameters> = persistentMapOf(),
    val parameterKey: String? = null,
    val settingsOpen: Boolean = false,
    val selectedService: String? = null,
    val busy: Boolean = false,
    val works: MediaWorksState = MediaWorksState(),
    val preview: MediaPreviewState? = null,
    val deleteConfirmation: MediaWork? = null,
    val error: String? = null,
)

sealed interface MediaAction {
    data object Refresh : MediaAction

    data class Parameters(val draftKey: String, val parameters: MediaParameters? = null) :
        MediaAction

    data class Mode(val draftKey: String, val parameters: MediaParameters?) : MediaAction

    data object CloseParameters : MediaAction

    data object Settings : MediaAction

    data object ManagePlugin : MediaAction

    data object CloseSettings : MediaAction

    data class Service(val key: String?) : MediaAction

    data class AddService(val preset: MediaPreset) : MediaAction

    data class WriteService(val namespace: String, val id: String, val value: ToolPayload.Obj?) :
        MediaAction

    data class WriteSetting(val namespace: String, val path: List<String>, val value: ToolPayload) :
        MediaAction

    data class SetCredential(val ref: String, val key: ProviderKey) : MediaAction

    data class ClearCredential(val ref: String) : MediaAction

    data class Default(
        val namespace: String,
        val kind: MediaKind,
        val serviceId: String,
        val modelId: String,
    ) : MediaAction

    data class Works(val kind: MediaKind? = null) : MediaAction

    data object MoreWorks : MediaAction

    data object PreviousWorks : MediaAction

    data object CloseWorks : MediaAction

    data class Preview(val work: MediaWork, val item: Int = 0) : MediaAction

    data class PreviewPosition(val work: Int, val item: Int) : MediaAction

    data object ClosePreview : MediaAction

    data class Export(
        val work: MediaWork,
        val item: GeneratedMediaItem,
        val operation: ImageExportOperation,
    ) : MediaAction

    data class CopyPrompt(val work: MediaWork) : MediaAction

    data class Reuse(
        val work: MediaWork,
        val send: Boolean = false,
        val reference: GeneratedMediaItem? = null,
        val video: Boolean = false,
    ) : MediaAction

    data class CancelJob(val sessionId: String, val job: String) : MediaAction

    data class Delete(val work: MediaWork?) : MediaAction

    data object ConfirmDelete : MediaAction

    data class GoConversation(val sessionId: String) : MediaAction
}

data class MediaDispatch(
    val hostKey: String,
    val sessionId: String?,
    val effect: MediaEffect,
    val owner: dev.fughetta.dshcompose.domain.media.MediaRepository,
    val muxGeneration: dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration?,
)

sealed interface MediaEffect {
    data class Compose(
        val prompt: String,
        val parameters: MediaParameters,
        val reference: GeneratedMediaItem?,
        val send: Boolean,
        val referenceSessionId: String? = null,
    ) : MediaEffect

    data class ImageExport(
        val sessionId: String,
        val item: GeneratedMediaItem,
        val operation: ImageExportOperation,
    ) : MediaEffect

    data class ShareFile(val path: String, val name: String, val mime: String) : MediaEffect

    data class GoConversation(val sessionId: String) : MediaEffect

    data class Message(val code: String) : MediaEffect

    data object OpenPlugins : MediaEffect
}

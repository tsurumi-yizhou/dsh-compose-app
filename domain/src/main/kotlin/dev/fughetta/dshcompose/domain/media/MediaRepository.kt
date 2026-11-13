package dev.fughetta.dshcompose.domain.media

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.providers.ProviderKey
import dev.fughetta.dshcompose.domain.settings.SettingsNamespace
import kotlinx.coroutines.flow.StateFlow

data class MediaLibraryPage(val page: MediaWorksPage, val local: Boolean)

interface MediaRepository : AutoCloseable {
    fun modes(host: String): Map<String, MediaParameters>

    fun rememberModes(host: String, modes: Map<String, MediaParameters>)

    fun preferredKind(host: String): MediaKind

    fun preferredParameters(host: String, kind: MediaKind): MediaParameters?

    fun rememberParameters(host: String, parameters: MediaParameters)

    suspend fun copyImage(
        source: dev.fughetta.dshcompose.domain.images.ReplyImageSource,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        workspace: suspend (String, dev.fughetta.dshcompose.domain.ByteSink, Long) -> Unit,
    ): String?

    suspend fun imageMime(file: String): String?

    val connection: StateFlow<MediaRepository?>
    val generation: ConnectionGeneration?
    val active: Boolean

    suspend fun readMediaCatalog(): MediaCatalog

    suspend fun writeMediaService(
        namespace: SettingsNamespace,
        serviceId: String,
        replacement: ToolPayload.Obj?,
    ): SettingsNamespace

    suspend fun writeMediaDefault(
        namespace: SettingsNamespace,
        kind: MediaKind,
        serviceId: String,
        modelId: String,
    ): SettingsNamespace

    suspend fun writeMediaSetting(
        namespace: SettingsNamespace,
        path: List<String>,
        value: ToolPayload,
    ): SettingsNamespace

    suspend fun setProviderKey(ref: String, key: ProviderKey)

    suspend fun clearProviderKey(ref: String)

    suspend fun killJob(sessionId: String, job: String): Boolean

    suspend fun ingest(host: String, session: String, updates: Collection<MediaTranscriptItem>)

    suspend fun page(host: String, cursor: String?, kind: MediaKind?): MediaLibraryPage

    suspend fun delete(host: String, work: MediaWork)

    suspend fun stageVideo(
        work: MediaWork,
        item: GeneratedMediaItem,
        progress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): String
}

package dev.fughetta.dshcompose.media

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.GeneratedMediaItem
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.domain.media.MediaParameters
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.ui.ImageExportOperation
import dev.fughetta.dshcompose.ui.media.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Generation is always a Host chat request; the App never invokes provider endpoints itself. */
class MediaGenerationViewModel(
    private val context: Context,
    private val repository: MediaRepository,
    parent: CoroutineScope? = null,
) : ViewModel() {
    private val lifetime = parent ?: viewModelScope

    override fun onCleared() {
        close()
    }

    private val scope =
        CoroutineScope(lifetime.coroutineContext + SupervisorJob(lifetime.coroutineContext[Job]))
    private val mutable = MutableStateFlow(MediaGenerationState())
    val state = mutable.asStateFlow()
    private val events = MutableSharedFlow<MediaDispatch>(extraBufferCapacity = 16)
    val effects = events.asSharedFlow()
    private var client: MediaRepository? = null
    private var hostKey = ""
    private var sessionId: String? = null
    @Volatile private var generation = 0
    @Volatile private var previewTicket = 0
    private var catalogJob: Job? = null
    private var worksJob: Job? = null
    private var previewJob: Job? = null
    private var exportJob: Job? = null
    private var previewDirectory: File? = null
    private var loadedSession: String? = null
    private var loadedEntries: Map<String, MediaTranscriptItem>? = null
    private val modeOrder = ArrayDeque<String>()
    private val previousPages = mutableListOf<String?>()
    private var currentCursor: String? = null
    private var storagePermission: (suspend () -> Boolean)? = null

    fun attachStoragePermission(request: (suspend () -> Boolean)?) {
        storagePermission = request
    }

    fun connect(client: MediaRepository?, hostKey: String) {
        generation++
        catalogJob?.cancel()
        worksJob?.cancel()
        previewJob?.cancel()
        exportJob?.cancel()
        cleanupPreview()
        this.client = client
        this.hostKey = hostKey
        loadedSession = null
        loadedEntries = null
        previousPages.clear()
        currentCursor = null
        modeOrder.clear()
        val modes = runCatching {
            val entries = repository.modes(hostKey).entries.map { it.key to it.value }.takeLast(40)
            modeOrder.addAll(entries.map { it.first })
            entries.toMap().toPersistentMap()
        }
            .getOrDefault(persistentMapOf())
        mutable.value = MediaGenerationState(modes = modes)
        if (client != null) refreshCatalog()
    }

    fun session(id: String?) {
        if (sessionId != id) {
            sessionId = id
            loadedSession = null
            loadedEntries = null
            closePreview()
        }
    }

    fun move(from: String, to: String) {
        val params = state.value.modes[from] ?: return
        modeOrder.remove(from)
        modeOrder.remove(to)
        modeOrder.addLast(to)
        mutable.update { it.copy(modes = it.modes.removing(from).putting(to, params)) }
        persistModes()
    }

    fun mode(draftKey: String, parameters: MediaParameters?) {
        storeMode(draftKey, parameters?.let { state.value.catalog.fit(it) })
    }

    private fun storeMode(draftKey: String, parameters: MediaParameters?) {
        modeOrder.remove(draftKey)
        var next = state.value.modes.removing(draftKey)
        if (parameters != null) {
            modeOrder.addLast(draftKey)
            next = next.putting(draftKey, parameters)
        }
        while (modeOrder.size > 40) next = next.removing(modeOrder.removeFirst())
        mutable.update { it.copy(modes = next) }
        parameters?.let { repository.rememberParameters(hostKey, it) }
        persistModes()
    }

    private fun persistModes() {
        repository.rememberModes(
            hostKey,
            modeOrder
                .filter { it != "new" }
                .mapNotNull { key -> state.value.modes[key]?.let { key to it } }
                .toMap(),
        )
    }

    fun preparePrompt(draftKey: String, text: String): String {
        val existing = MediaGenerationHeader.read(text)
        if (existing?.parameters != null) return text
        val params = state.value.modes[draftKey] ?: return text
        existing?.error?.let { throw MediaHeaderException(it) }
        if (state.value.catalog.resolve(params.model, params.kind) == null)
            throw OperationFailure("media/model-unavailable")
        return MediaGenerationHeader.format(params, text)
    }

    /**
     * Restores editing controls from a stored generation header, returning just the editable
     * description.
     */
    fun restorePrompt(draftKey: String, text: String): String {
        val parsed = MediaGenerationHeader.read(text)
        val params = parsed?.parameters
        if (
            params == null ||
                state.value.catalog.status != MediaCatalogStatus.Ready ||
                state.value.catalog.entries.isEmpty()
        ) {
            mode(draftKey, null)
            return text
        }
        val model = params.model
        val matches =
            state.value.catalog.entries.filter { entry ->
                entry.model.kind == params.kind &&
                    model != null &&
                    (if (model.pinned)
                        entry.service.id == model.service && entry.model.id == model.id
                    else entry.model.label == model.label)
            }
        val entry =
            if (model == null) state.value.catalog.default(params.kind) else matches.singleOrNull()
        if (entry == null) {
            mode(draftKey, null)
            return text
        }
        val next = params.copy(model = entry.ref)
        val fitted = state.value.catalog.fit(next)
        if (
            params.aspect != null && fitted.aspect != params.aspect ||
                fitted.count != params.count ||
                params.quality != null && fitted.quality != params.quality ||
                params.seconds != null && fitted.seconds != params.seconds ||
                params.resolution != null && fitted.resolution != params.resolution
        ) {
            mode(draftKey, null)
            return text
        }
        storeMode(draftKey, next)
        return parsed.prompt ?: text
    }

    fun ingest(sessionId: String, entries: Map<String, MediaTranscriptItem>) {
        val previous = loadedEntries.takeIf { loadedSession == sessionId }
        loadedSession = sessionId
        loadedEntries = entries
        val updates = entries.values.filter { previous?.get(it.key) !== it }
        if (updates.isEmpty()) return
        val host = hostKey
        scope.launch { repository.ingest(host, sessionId, updates) }
    }

    fun refreshCatalog() {
        val owner = client ?: return
        val epoch = generation
        catalogJob?.cancel()
        catalogJob = scope.launch {
            try {
                val catalog = owner.readMediaCatalog()
                if (epoch == generation) mutable.update { it.copy(catalog = catalog, error = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(error = code(error)) }
            }
        }
    }

    fun action(action: MediaAction) {
        when (action) {
            MediaAction.Refresh -> refreshCatalog()
            is MediaAction.Mode -> mode(action.draftKey, action.parameters)
            is MediaAction.Parameters -> {
                if (state.value.modes[action.draftKey] == null) {
                    val kind = repository.preferredKind(hostKey)
                    val last =
                        runCatching {
                            repository.preferredParameters(hostKey, kind)
                        }
                            .getOrNull() ?: MediaParameters(kind)
                    mode(
                        action.draftKey,
                        state.value.catalog.fit(action.parameters ?: last, start = true),
                    )
                }
                mutable.update { it.copy(parameterKey = action.draftKey) }
            }
            MediaAction.CloseParameters -> mutable.update { it.copy(parameterKey = null) }
            MediaAction.Settings -> {
                mutable.update { it.copy(settingsOpen = true) }
                refreshCatalog()
            }
            MediaAction.ManagePlugin -> emit(sessionId, MediaEffect.OpenPlugins)
            MediaAction.CloseSettings ->
                mutable.update { it.copy(settingsOpen = false, selectedService = null) }
            is MediaAction.Service -> mutable.update { it.copy(selectedService = action.key) }
            is MediaAction.AddService -> addService(action.preset)
            is MediaAction.WriteService ->
                mutate { owner ->
                    owner.writeMediaService(namespace(action.namespace), action.id, action.value)
                }
            is MediaAction.WriteSetting ->
                mutate { owner ->
                    owner.writeMediaSetting(namespace(action.namespace), action.path, action.value)
                }
            is MediaAction.SetCredential ->
                mutate(clear = action.key) { owner -> owner.setProviderKey(action.ref, action.key) }
            is MediaAction.ClearCredential -> mutate { owner -> owner.clearProviderKey(action.ref) }
            is MediaAction.Default ->
                mutate { owner ->
                    owner.writeMediaDefault(
                        namespace(action.namespace),
                        action.kind,
                        action.serviceId,
                        action.modelId,
                    )
                }
            is MediaAction.Works -> {
                previousPages.clear()
                currentCursor = null
                mutable.update {
                    it.copy(works = MediaWorksState(open = true, filter = action.kind))
                }
                loadWorks(null)
            }
            MediaAction.MoreWorks ->
                state.value.works.next?.let { next ->
                    previousPages.add(currentCursor)
                    currentCursor = next
                    loadWorks(next)
                }
            MediaAction.PreviousWorks ->
                if (previousPages.isNotEmpty()) {
                    currentCursor = previousPages.removeAt(previousPages.lastIndex)
                    loadWorks(currentCursor)
                }
            MediaAction.CloseWorks -> {
                worksJob?.cancel()
                mutable.update { it.copy(works = MediaWorksState()) }
            }
            is MediaAction.Preview -> preview(action.work, action.item)
            is MediaAction.PreviewPosition -> previewPosition(action.work, action.item)
            MediaAction.ClosePreview -> closePreview()
            is MediaAction.Export -> export(action.work, action.item, action.operation)
            is MediaAction.CopyPrompt ->
                action.work.media.copyPrompt?.let {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("", it))
                }
            is MediaAction.Reuse -> reuse(action)
            is MediaAction.CancelJob ->
                mutate { owner ->
                    if (!owner.killJob(action.sessionId, action.job))
                        throw OperationFailure("media/job-not-stopped")
                }
            is MediaAction.Delete -> mutable.update { it.copy(deleteConfirmation = action.work) }
            MediaAction.ConfirmDelete ->
                state.value.deleteConfirmation?.let { work ->
                    val host = hostKey
                    val epoch = generation
                    mutate {
                        it.delete(host, work)
                        if (epoch == generation)
                            mutable.update { current ->
                                current.copy(
                                    deleteConfirmation = null,
                                    works =
                                        current.works.copy(
                                            items =
                                                current.works.items
                                                    .filter { row -> row.key != work.key }
                                                    .toPersistentList(),
                                        ),
                                )
                            }
                    }
                }
            is MediaAction.GoConversation ->
                emit(action.sessionId, MediaEffect.GoConversation(action.sessionId))
        }
    }

    private fun namespace(name: String) =
        state.value.catalog.namespaces.find { it.ns == name }
            ?: throw OperationFailure("media/namespace-missing")

    private fun mutate(clear: AutoCloseable? = null, operation: suspend (MediaRepository) -> Unit) {
        val owner = client
        if (owner == null || state.value.busy) {
            clear?.close()
            return
        }
        val epoch = generation
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                if (epoch == generation) {
                    operation(owner)
                    if (epoch == generation) refreshCatalog()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(error = code(error)) }
            } finally {
                clear?.close()
                if (epoch == generation) mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun addService(preset: MediaPreset) {
        val catalog = state.value.catalog
        val services = catalog.services.filter { it.namespace == preset.namespace }
        val base =
            preset.id.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank {
                "media_service"
            }
        var id = base
        var suffix = 2
        while (services.any { it.id == id }) id = "${base}_${suffix++}"
        val donor = services.find { it.provider == preset.provider }
        val stem =
            "DSH_MEDIA_${preset.keyGroup.uppercase().replace(Regex("[^A-Z0-9]+"), "_")}".take(100)
        var keyRef = donor?.keyRef ?: "${stem}_API_KEY"
        suffix = 2
        while (donor == null && services.any { it.keyRef == keyRef }) keyRef =
            "${stem}_${suffix++}_API_KEY"
        val models =
            preset.models.filter {
                catalog.models[it]?.let { model ->
                    model.kind == preset.kind && model.profile == preset.profile
                } == true
            }
        if (models.isEmpty()) {
            mutable.update { it.copy(error = "media/no-models") }
            return
        }
        val service =
            ToolPayload.Obj(
                mapOf(
                    "id" to ToolPayload.Text(id),
                    "label" to ToolPayload.Text(preset.label),
                    "enabled" to ToolPayload.Bool(true),
                    "provider" to ToolPayload.Text(preset.provider),
                    "profile" to ToolPayload.Text(preset.profile),
                    "baseUrl" to ToolPayload.Text(preset.baseUrl),
                    "keyRef" to ToolPayload.Text(keyRef),
                    "models" to ToolPayload.Text(models.joinToString(", ")),
                    "responseFormat" to ToolPayload.Text(preset.responseFormat),
                ),
            )
        mutate { it.writeMediaService(namespace(preset.namespace), id, service) }
        mutable.update { it.copy(selectedService = "${preset.namespace}|$id") }
    }

    private fun loadWorks(cursor: String?) {
        val owner = client ?: return
        val host = hostKey
        val epoch = generation
        val kind = state.value.works.filter
        worksJob?.cancel()
        mutable.update {
            it.copy(
                works =
                    it.works.copy(
                        loading = true,
                        error = null,
                        hasPrevious = previousPages.isNotEmpty(),
                    ),
            )
        }
        worksJob = scope.launch {
            try {
                val result = owner.page(host, cursor, kind)
                val page = result.page
                val local = result.local
                if (epoch == generation)
                    mutable.update {
                        it.copy(
                            works =
                                it.works.copy(
                                    items = page.items.toPersistentList(),
                                    next = page.next,
                                    loading = false,
                                    local = local,
                                ),
                        )
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation)
                    mutable.update {
                        it.copy(works = it.works.copy(loading = false, error = code(error)))
                    }
            }
        }
    }

    private fun reuse(action: MediaAction.Reuse) {
        val media = action.work.media
        val kind = if (action.video) MediaKind.Video else media.kind
        val request = media.request
        val model =
            state.value.catalog.entries
                .filter {
                    it.model.kind == kind &&
                        it.model.id == request.model &&
                        it.service.provider == request.provider
                }
                .singleOrNull()
                ?.ref
        val parameters =
            state.value.catalog.fit(
                MediaParameters(
                    kind,
                    model,
                    request.aspect,
                    request.count ?: media.items.size.coerceAtLeast(1),
                    request.quality,
                    request.seconds?.toInt(),
                    request.resolution,
                ),
                start = true,
            )
        val destination = sessionId
        mode(destination ?: "new", parameters)
        emit(
            destination,
            MediaEffect.Compose(
                media.prompt ?: media.revisedPrompt.orEmpty(),
                parameters,
                action.reference,
                action.send,
                action.work.sessionId,
            ),
        )
    }

    private fun preview(work: MediaWork, item: Int) {
        val list =
            state.value.works.items.takeIf { rows -> rows.any { it.key == work.key } }
                ?: persistentListOf(work)
        mutable.update {
            it.copy(
                preview =
                    MediaPreviewState(
                        list,
                        list.indexOfFirst { row -> row.key == work.key },
                        item.coerceIn(0, work.media.items.lastIndex.coerceAtLeast(0)),
                    ),
            )
        }
        prepareVideo()
    }

    private fun previewPosition(work: Int, item: Int) {
        val current = state.value.preview ?: return
        if (work !in current.works.indices || item !in current.works[work].media.items.indices)
            return
        mutable.update {
            it.copy(
                preview =
                    current.copy(
                        workIndex = work,
                        itemIndex = item,
                        videoPath = null,
                        error = null,
                    ),
            )
        }
        prepareVideo()
    }

    private fun prepareVideo() {
        val ticket = ++previewTicket
        previewJob?.cancel()
        cleanupPreview()
        val preview = state.value.preview ?: return
        val work = preview.works[preview.workIndex]
        val item = work.media.items.getOrNull(preview.itemIndex) ?: return
        if (work.media.kind != MediaKind.Video) return
        val owner = client ?: return
        val epoch = generation
        mutable.update { it.copy(preview = it.preview?.copy(downloading = true, progress = null)) }
        previewJob = scope.launch {
            var file: File? = null
            try {
                val prepared =
                    File(
                        owner.stageVideo(work, item) { received, total ->
                            if (epoch == generation && ticket == previewTicket)
                                mutable.update {
                                    it.copy(
                                        preview =
                                            it.preview?.copy(
                                                progress =
                                                    total
                                                        ?.takeIf { size -> size > 0 }
                                                        ?.let { size -> received.toFloat() / size },
                                            ),
                                    )
                                }
                        },
                    )
                file = prepared
                if (epoch == generation && ticket == previewTicket) {
                    previewDirectory = prepared.parentFile
                    mutable.update {
                        it.copy(
                            preview =
                                it.preview?.copy(videoPath = prepared.path, downloading = false),
                        )
                    }
                    file = null
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation && ticket == previewTicket)
                    mutable.update {
                        it.copy(
                            preview = it.preview?.copy(downloading = false, error = code(error)),
                        )
                    }
            } finally {
                file?.parentFile?.deleteRecursively()
            }
        }
    }

    private fun export(work: MediaWork, item: GeneratedMediaItem, operation: ImageExportOperation) {
        if (work.media.kind == MediaKind.Image) {
            emit(work.sessionId, MediaEffect.ImageExport(work.sessionId, item, operation))
            return
        }
        if (exportJob?.isActive == true) return
        val owner = client ?: return
        val host = hostKey
        val epoch = generation
        val muxGeneration = owner.generation
        exportJob = scope.launch {
            var file: File? = null
            try {
                val prepared = File(owner.stageVideo(work, item))
                file = prepared
                if (epoch != generation) return@launch
                if (operation == ImageExportOperation.Share) {
                    if (client !== owner || !owner.active || owner.generation != muxGeneration)
                        return@launch
                    events.emit(
                        MediaDispatch(
                            host,
                            work.sessionId,
                            MediaEffect.ShareFile(
                                prepared.path,
                                prepared.name,
                                item.mime ?: "video/mp4",
                            ),
                            owner,
                            muxGeneration,
                        ),
                    )
                    val retained = prepared.parentFile
                    file = null
                    scope.launch(Dispatchers.IO + NonCancellable) {
                        delay(300_000)
                        retained?.deleteRecursively()
                    }
                } else {
                    if (
                        Build.VERSION.SDK_INT < 29 &&
                            context.checkSelfPermission(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (storagePermission?.invoke() != true)
                            throw OperationFailure("media/permission")
                        if (
                            epoch != generation ||
                                client !== owner ||
                                !owner.active ||
                                owner.generation != muxGeneration
                        )
                            return@launch
                    }
                    withContext(Dispatchers.IO) {
                        saveGeneratedVideo(context, prepared, item.mime ?: "video/mp4")
                    }
                    if (
                        epoch == generation &&
                            client === owner &&
                            owner.active &&
                            owner.generation == muxGeneration
                    )
                        events.emit(
                            MediaDispatch(
                                host,
                                work.sessionId,
                                MediaEffect.Message("media/saved"),
                                owner,
                                muxGeneration,
                            ),
                        )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation) mutable.update { it.copy(error = code(error)) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    file?.parentFile?.deleteRecursively()
                }
            }
        }
    }

    private fun emit(session: String?, effect: MediaEffect) {
        val owner = client ?: return
        val muxGeneration = owner.generation
        val host = hostKey
        val epoch = generation
        scope.launch {
            if (
                epoch == generation &&
                    client === owner &&
                    owner.active &&
                    owner.generation == muxGeneration
            )
                events.emit(MediaDispatch(host, session, effect, owner, muxGeneration))
        }
    }

    private fun closePreview() {
        previewTicket++
        previewJob?.cancel()
        cleanupPreview()
        mutable.update { it.copy(preview = null) }
    }

    private fun cleanupPreview() {
        previewDirectory?.let { file ->
            scope.launch(Dispatchers.IO + NonCancellable) { file.deleteRecursively() }
        }
        previewDirectory = null
    }

    private fun code(error: Exception) =
        operationErrorCode(error)
            ?: when (error) {
                is MediaHeaderException -> error.code
                is SecurityException -> "media/permission"
                else -> "media/request-failed"
            }

    fun close() {
        generation++
        closePreview()
        scope.cancel()
    }
}

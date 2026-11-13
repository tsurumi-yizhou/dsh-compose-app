package dev.fughetta.dshcompose.data.media

import android.content.Context
import dev.fughetta.dshcompose.data.attachments.AttachmentMetadata
import dev.fughetta.dshcompose.data.domainOperation
import dev.fughetta.dshcompose.data.images.ReplyImageDownloads
import dev.fughetta.dshcompose.data.outputStream
import dev.fughetta.dshcompose.data.settings.localPreferences
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.providers.ProviderKey
import dev.fughetta.dshcompose.domain.settings.SettingsNamespace
import dev.fughetta.dshcompose.protocol.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

fun mediaCatalogMetadata(namespace: SettingsNamespaceView): JsonObject? {
    val node = namespace.schema.fields()?.get("services") ?: return null
    return listOf(node, node.effective).firstNotNullOfOrNull {
        ((it.meta.raw["extra"] as? ToolPayload.Obj)?.get("dsh-compose/media/catalog")
                as? ToolPayload.Obj)
            ?.toJsonElement() as? JsonObject
    }
}

/** Local media index plus one generation's catalog, settings, and remote media operations. */
class EngineMediaRepository
private constructor(
    private val store: Store,
    @Volatile private var client: ProtocolClient? = null,
    private val ownsStore: Boolean = false,
) : MediaRepository {
    private class Store(val context: Context) {
        val index by lazy { MediaWorkIndex(context) }
        val preferences by lazy { context.localPreferences("media-generation") }
        val images = lazy { ReplyImageDownloads() }
        val lock = Mutex()
    }

    constructor(context: Context) : this(Store(context), ownsStore = true)

    override fun modes(host: String): Map<String, MediaParameters> =
        decodeMediaModes(store.preferences.getString("modes:$host", null))

    override fun rememberModes(host: String, modes: Map<String, MediaParameters>) {
        store.preferences.edit().putString("modes:$host", encodeMediaModes(modes)).apply()
    }

    override fun preferredKind(host: String): MediaKind =
        if (store.preferences.getString("kind:$host", "image") == "video") MediaKind.Video
        else MediaKind.Image

    override fun preferredParameters(host: String, kind: MediaKind): MediaParameters? =
        store.preferences.getString("last:$host:${kind.wire}", null)?.let(::decodeMediaParameters)

    override fun rememberParameters(host: String, parameters: MediaParameters) {
        store.preferences
            .edit()
            .putString("last:$host:${parameters.kind.wire}", encodeMediaParameters(parameters))
            .putString("kind:$host", parameters.kind.wire)
            .apply()
    }

    override suspend fun copyImage(
        source: dev.fughetta.dshcompose.domain.images.ReplyImageSource,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        workspace: suspend (String, dev.fughetta.dshcompose.domain.ByteSink, Long) -> Unit,
    ): String? =
        store.images.value.copy(source, destination.outputStream()) { path, output, limit ->
            workspace(path, output::write, limit)
        }

    override suspend fun imageMime(file: String): String? =
        withContext(Dispatchers.IO) { AttachmentMetadata.imageMime(File(file)) }

    override fun close() {
        disconnect()
        client = null
        mutableConnection.value = null
        if (ownsStore && store.images.isInitialized()) store.images.value.close()
    }

    private var selected: EngineMediaRepository? = null
    private val mutableConnection = MutableStateFlow<MediaRepository?>(null)
    override val connection = mutableConnection.asStateFlow()
    override val generation: ConnectionGeneration? = client?.mux?.state?.value?.generation
    override val active: Boolean
        get() = client?.let { it.mux.state.value.generation == generation } == true

    fun connect(client: ProtocolClient?) {
        if (
            selected?.client === client &&
                selected?.generation == client?.mux?.state?.value?.generation
        )
            return
        disconnect()
        if (client == null) return
        val next = EngineMediaRepository(store, client)
        next.mutableConnection.value = next
        selected = next
        mutableConnection.value = next
    }

    fun disconnect() {
        selected?.let {
            it.client = null
            it.mutableConnection.value = null
        }
        selected = null
        mutableConnection.value = null
    }

    private suspend fun <T> withClient(block: suspend ProtocolClient.() -> T): T {
        val owner = selected ?: this
        val client = owner.client ?: throw CancellationException("Media connection retired")
        if (!owner.active) throw CancellationException("Media connection retired")
        return domainOperation {
            client.block().also {
                if (owner.client !== client || !owner.active)
                    throw CancellationException("Media connection retired")
            }
        }
    }

    override suspend fun setProviderKey(ref: String, key: ProviderKey) = withClient {
        this.setProviderKey(ref, key)
    }

    override suspend fun clearProviderKey(ref: String) = withClient { this.clearProviderKey(ref) }

    override suspend fun killJob(sessionId: String, job: String): Boolean = withClient {
        this.killJob(sessionId, job)
    }

    override suspend fun ingest(
        host: String,
        session: String,
        updates: Collection<MediaTranscriptItem>,
    ) =
        withContext(Dispatchers.IO) {
            store.lock.withLock { store.index.ingest(host, session, updates) }
        }

    override suspend fun page(
        host: String,
        cursor: String?,
        kind: MediaKind?,
    ): MediaLibraryPage = withClient {
        val local = connection !is HostConnection
        val page =
            if (local)
                withContext(Dispatchers.IO) {
                    store.lock.withLock { store.index.page(host, cursor, kind) }
                }
            else readMediaWorks(cursor, kind)
        MediaLibraryPage(page, local)
    }

    override suspend fun delete(host: String, work: MediaWork) {
        withClient { deleteMediaWork(work) }
        withContext(Dispatchers.IO) {
            store.lock.withLock { store.index.remove(host, work.media.rid ?: work.key) }
        }
    }

    override suspend fun stageVideo(
        work: MediaWork,
        item: GeneratedMediaItem,
        progress: suspend (Long, Long?) -> Unit,
    ): String {
        val directory =
            File(store.context.cacheDir, "artifacts/generated-media/${UUID.randomUUID()}")
        try {
            return withClient {
                val owner = this
                withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val name =
                        item.name
                            .filter { it >= ' ' && it !in "/\\:*?\"<>|" }
                            .take(120)
                            .ifBlank { "video.mp4" }
                    File(directory, name).also { file ->
                        file.outputStream().use {
                            dev.fughetta.dshcompose.data.workspace
                                .EngineWorkspaceFilesRepository(owner)
                                .copy(
                                    work.sessionId,
                                    item.path,
                                    it::write,
                                    maximumBytes = 512L * 1024 * 1024,
                                    onProgress = progress,
                                )
                        }
                    }
                }
            }
                .absolutePath
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
            throw error
        }
    }

    override suspend fun readMediaCatalog(): MediaCatalog = withClient {
        withContext(Dispatchers.Default) {
            val namespaces =
                describeSettings().namespaces.filter { mediaCatalogMetadata(it) != null }
            if (namespaces.isEmpty()) return@withContext MediaCatalog(MediaCatalogStatus.Missing)
            val services = namespaces.flatMap { namespace ->
                namespace.value.npObjects("services").mapNotNull { raw ->
                    val id = raw.npString("id") ?: return@mapNotNull null
                    val key = raw.npString("keyRef") ?: return@mapNotNull null
                    val listed =
                        (raw["models"] as? JsonPrimitive)
                            ?.contentOrNull
                            ?.split(',')
                            ?.map(String::trim)
                            ?.filter(String::isNotEmpty) ?: raw.npStrings("models")
                    MediaServiceConfig(
                        namespace.ns,
                        id,
                        raw.npString("label").orEmpty(),
                        raw.npBool("enabled"),
                        raw.npString("provider").orEmpty(),
                        raw.npString("baseUrl").orEmpty(),
                        raw.npString("profile").orEmpty(),
                        raw.npString("responseFormat").orEmpty(),
                        listed,
                        key,
                        raw.toToolPayload() as ToolPayload.Obj,
                    )
                }
            }
            val credentials =
                describeCredentials(services.map { it.keyRef }.filter(String::isNotEmpty))
            val models = linkedMapOf<String, MediaCatalogModel>()
            val entries = mutableListOf<MediaCatalogEntry>()
            val presets = mutableListOf<MediaPreset>()
            var valid = false
            for (namespace in namespaces) {
                val metadata = mediaCatalogMetadata(namespace) ?: continue
                if (
                    (metadata["v"] as? JsonPrimitive)?.intOrNull != 1 ||
                        metadata.npString("contract") !in
                            setOf(null, "dsh-compose/media/catalog") ||
                        metadata["models"] !is JsonArray
                )
                    continue
                val localModels = linkedMapOf<String, MediaCatalogModel>()
                var malformed = false
                for (raw in metadata.npObjects("models")) {
                    val id = raw.npString("id")
                    val label = raw.npString("label")
                    if (id.isNullOrEmpty() || label.isNullOrEmpty() || id in localModels) {
                        malformed = true
                        break
                    }
                    val kind =
                        when (raw.npString("kind")) {
                            "image" -> MediaKind.Image
                            "video" -> MediaKind.Video
                            else -> continue
                        }
                    val frames = raw["frames"] as? JsonObject
                    localModels[id] =
                        MediaCatalogModel(
                            id,
                            label,
                            kind,
                            raw.npString("profile"),
                            raw.npStrings("ratios").filter {
                                it.matches(Regex("[1-9][0-9]?:[1-9][0-9]?"))
                            },
                            raw.npStrings("qualities").filter {
                                it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}"))
                            },
                            ((raw["maxN"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, 4),
                            (raw["durations"] as? JsonArray)
                                .orEmpty()
                                .mapNotNull {
                                    (it as? JsonPrimitive)?.intOrNull?.takeIf { value ->
                                        value in 1..60
                                    }
                                }
                                .distinct(),
                            raw.npStrings("resolutions").filter {
                                it.matches(Regex("[1-9][0-9]{2,3}p"))
                            },
                            ((raw["maxRefs"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 99),
                            frames?.npBool("first") == true,
                            frames?.npBool("last") == true,
                            raw.npBool("edit"),
                            (raw["price"] as? JsonObject)?.let { price ->
                                MediaPrice(
                                    (price["amount"] as? JsonPrimitive)?.doubleOrNull,
                                    price.npString("unit"),
                                    price.npString("currency"),
                                )
                            },
                        )
                }
                if (malformed) continue
                valid = true
                localModels.forEach { (id, value) -> models.putIfAbsent(id, value) }
                metadata.npObjects("presets").forEach { raw ->
                    val id = raw.npString("id") ?: return@forEach
                    val label = raw.npString("label") ?: return@forEach
                    val provider = raw.npString("provider") ?: return@forEach
                    val profile = raw.npString("profile") ?: return@forEach
                    val url = raw.npString("baseUrl") ?: return@forEach
                    val kind =
                        when (raw.npString("kind")) {
                            "image" -> MediaKind.Image
                            "video" -> MediaKind.Video
                            else -> return@forEach
                        }
                    presets.add(
                        MediaPreset(
                            namespace.ns,
                            id,
                            label,
                            raw.npString("labelEn"),
                            provider,
                            profile,
                            url,
                            raw.npString("responseFormat").orEmpty(),
                            raw.npStrings("models"),
                            kind,
                            raw.npString("keyGroup") ?: id.substringBefore('-'),
                        ),
                    )
                }
                val caps = metadata["capabilities"] as? JsonObject
                val max =
                    ((metadata["limits"] as? JsonObject)?.get("maxN") as? JsonPrimitive)?.intOrNull
                        ?: 4
                val hostMax =
                    ((namespace.value["limits"] as? JsonObject)?.get("maxImages") as? JsonPrimitive)
                        ?.intOrNull ?: 4
                val serviceCounts =
                    services
                        .filter { it.namespace == namespace.ns }
                        .groupingBy { it.id }
                        .eachCount()
                for (service in
                    services.filter {
                        it.namespace == namespace.ns &&
                            it.enabled &&
                            credentials[it.keyRef]?.configured == true &&
                            serviceCounts[it.id] == 1
                    }) {
                    for (id in service.models.distinct()) {
                        val model = localModels[id] ?: continue
                        if (
                            caps?.get(model.kind.wire)?.let { it != JsonPrimitive(true) } == true ||
                                model.profile != null && model.profile != service.profile ||
                                !MediaGenerationHeader.markable(service.id, model.id) ||
                                !MediaGenerationHeader.expressible(model.label)
                        )
                            continue
                        entries.add(
                            MediaCatalogEntry(
                                service,
                                model.copy(maxN = minOf(model.maxN, max, hostMax).coerceIn(1, 4)),
                            ),
                        )
                    }
                }
            }
            val duplicates =
                entries.groupingBy { "${it.service.id}\u0000${it.model.id}" }.eachCount()
            MediaCatalog(
                if (valid) MediaCatalogStatus.Ready else MediaCatalogStatus.Invalid,
                namespaces.map { it.toDomain() },
                models,
                services,
                entries.filter { duplicates["${it.service.id}\u0000${it.model.id}"] == 1 },
                presets.distinctBy { "${it.namespace}/${it.id}" },
                credentials,
            )
        }
    }

    override suspend fun writeMediaService(
        namespace: SettingsNamespace,
        serviceId: String,
        replacement: ToolPayload.Obj?,
    ): SettingsNamespace = withClient {
        val view = namespace.toView()
        val list = view.value["services"] as? JsonArray ?: JsonArray(emptyList())
        val indices = list.indices.filter { (list[it] as? JsonObject)?.npString("id") == serviceId }
        if (indices.size > 1) throw HostRemoteException("media/duplicate-service")
        val next = list.filterIndexed { index, _ -> index !in indices }.toMutableList()
        replacement?.toJsonElement()?.let {
            if (indices.isEmpty()) next.add(it) else next.add(indices.single(), it)
        }
        mutateSettingsView(view, listOf(SettingsOp(listOf("services"), JsonArray(next)))).toDomain()
    }

    override suspend fun writeMediaDefault(
        namespace: SettingsNamespace,
        kind: MediaKind,
        serviceId: String,
        modelId: String,
    ): SettingsNamespace = withClient {
        val view = namespace.toView()
        val defaults = (view.value["defaults"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        defaults["${kind.wire}ServiceId"] = JsonPrimitive(serviceId)
        defaults["${kind.wire}ModelId"] = JsonPrimitive(modelId)
        mutateSettingsView(
                view,
                listOf(SettingsOp(listOf("defaults"), JsonObject(defaults))),
            )
            .toDomain()
    }

    override suspend fun writeMediaSetting(
        namespace: SettingsNamespace,
        path: List<String>,
        value: ToolPayload,
    ): SettingsNamespace = withClient {
        mutateSettingsView(namespace.toView(), listOf(SettingsOp(path, value.toJsonElement())))
            .toDomain()
    }
}

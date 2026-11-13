package dev.fughetta.dshcompose.data.messages

import android.content.Context
import dev.fughetta.dshcompose.data.attachments.*
import dev.fughetta.dshcompose.domain.attachments.*
import dev.fughetta.dshcompose.domain.attachments.AttachmentReference
import dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject

/**
 * Owns upload receipts and cancels preparations when their connection, generation or session
 * retires.
 */
class EngineAttachmentsRepository
private constructor(
    private val remote: ProtocolClient?,
    parent: CoroutineScope?,
    private val directory: File?,
    private val metadata: ((AttachmentSource) -> AttachmentSourceMetadata)?,
    private val open: ((AttachmentSource) -> java.io.InputStream?)?,
    private val dispatcher: CoroutineDispatcher,
) : AttachmentsRepository {
    internal constructor(
        source: ProtocolClient,
        parent: CoroutineScope,
    ) : this(source, parent, null, null, null, Dispatchers.IO)

    internal constructor(
        directory: File,
        metadata: (AttachmentSource) -> AttachmentSourceMetadata,
        open: (AttachmentSource) -> java.io.InputStream?,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(null, null, directory, metadata, open, dispatcher)

    constructor(
        context: Context,
    ) : this(
        File(context.cacheDir, "composer/${UUID.randomUUID()}"),
        metadata = { attachmentSourceMetadata(context.applicationContext.contentResolver, it) },
        open = {
            context.applicationContext.contentResolver.openInputStream(
                android.net.Uri.parse(it.uri),
            )
        },
    )

    private val source: ProtocolClient
        get() = remote ?: throw CancellationException("Attachment connection unavailable")

    private data class Key(val sessionId: String, val attachmentId: String)

    private val lifetime = parent?.let {
        CoroutineScope(it.coroutineContext + SupervisorJob(it.coroutineContext[Job]))
    }
    private val scope: CoroutineScope
        get() = checkNotNull(lifetime)

    private val guard = Any()
    private val uploads = mutableMapOf<Key, String>()
    private val requests = mutableMapOf<Job, String>()
    private val retiredSessions = mutableSetOf<String>()
    private var generation = remote?.mux?.state?.value?.generation
    private var closed = false

    init {
        if (remote != null)
            scope.launch(Dispatchers.Default) {
                source.mux.state
                    .map { it.generation }
                    .distinctUntilChanged()
                    .collect { observed ->
                        synchronized(guard) {
                            if (source.mux.state.value.generation == observed)
                                updateGeneration(observed)
                        }
                    }
            }
    }

    private fun updateGeneration(next: ConnectionGeneration?) {
        if (generation == next) return
        generation = next
        uploads.clear()
        requests.keys.toList().forEach { it.cancel() }
    }

    private fun ensureCurrent(sessionId: String, lease: ConnectionGeneration) {
        if (
            closed ||
                !scope.isActive ||
                source.mux.state.value.generation != lease ||
                sessionId in retiredSessions
        )
            throw CancellationException("Attachment owner retired")
    }

    private suspend fun <T> request(
        sessionId: String,
        expected: ConnectionGeneration? = null,
        operation: suspend (ConnectionGeneration) -> T,
    ): T {
        val lease =
            source.mux.state.value.generation
                ?: throw CancellationException("Attachment connection unavailable")
        val task =
            synchronized(guard) {
                if (expected != null && expected != lease)
                    throw CancellationException("Prepared attachment generation retired")
                updateGeneration(lease)
                ensureCurrent(sessionId, lease)
                scope
                    .async(Dispatchers.IO, start = CoroutineStart.LAZY) { operation(lease) }
                    .also { job ->
                        requests[job] = sessionId
                        job.invokeOnCompletion { synchronized(guard) { requests.remove(job) } }
                    }
            }
        task.start()
        return try {
            val result = task.await()
            synchronized(guard) { ensureCurrent(sessionId, lease) }
            result
        } finally {
            task.cancel()
        }
    }

    override suspend fun readImage(sessionId: String, reference: AttachmentReference): ByteArray =
        request(sessionId) { lease ->
            val bytes = source.readImage(sessionId, reference)
            synchronized(guard) { ensureCurrent(sessionId, lease) }
            bytes
        }

    override suspend fun prepare(
        sessionId: String,
        files: List<ComposerFile>,
        onProgress: suspend (AttachmentUploadProgress) -> Unit,
    ): PreparedMessageAttachments =
        request(sessionId) { lease ->
            val images = mutableListOf<JsonObject>()
            val receipts = mutableListOf<String>()
            for (file in files) {
                synchronized(guard) { ensureCurrent(sessionId, lease) }
                if (file.isImage) images += image(file)
                else {
                    val key = Key(sessionId, file.id)
                    val cached = synchronized(guard) { uploads[key] }
                    val uploaded =
                        cached
                            ?: source
                                .uploadFile(sessionId, File(file.path), file.name) { done, total ->
                                    synchronized(guard) { ensureCurrent(sessionId, lease) }
                                    onProgress(AttachmentUploadProgress(file.id, done, total))
                                }
                                .receiptId
                                .also { value ->
                                    synchronized(guard) {
                                        ensureCurrent(sessionId, lease)
                                        uploads[key] = value
                                    }
                                }
                    receipts += uploaded
                }
            }
            synchronized(guard) { ensureCurrent(sessionId, lease) }
            PreparedMessageAttachments(receipts, emptyList(), submission(sessionId, lease, images))
        }

    override suspend fun prepareRetry(pending: PendingPrompt): PendingPromptSubmission =
        request(pending.sessionId) { lease ->
            if (pending.home != lease.home)
                throw CancellationException("Pending prompt home changed")
            val images = pending.files.filter { it.isImage }.map { image(it) }
            synchronized(guard) { ensureCurrent(pending.sessionId, lease) }
            submission(pending.sessionId, lease, images)
        }

    private fun image(file: ComposerFile): JsonObject =
        promptImage(file.mediaType, File(file.path).readBytes(), file.name)

    private suspend fun submit(
        pending: PendingPrompt,
        images: List<JsonObject>,
    ): PromptSubmissionResult =
        try {
            source.prompt(
                pending.sessionId,
                pending.text,
                pending.mode,
                pending.requestId,
                images,
                pending.fileReceiptIds,
                pending.timeZone,
            )
            PromptSubmissionResult.Acknowledged
        } catch (error: Exception) {
            PromptSubmissionResult.Failed(
                error,
                (error as? HostRemoteException)?.takeIf { it.answeredByHost }?.code,
            )
        }

    private fun submission(
        sessionId: String,
        lease: ConnectionGeneration,
        images: List<JsonObject>,
    ): PendingPromptSubmission = { pending ->
        try {
            request(sessionId, lease) {
                if (pending.sessionId != sessionId || pending.home != lease.home)
                    throw CancellationException("Prepared prompt owner changed")
                submit(pending, images)
            }
        } catch (error: Exception) {
            PromptSubmissionResult.Failed(error)
        }
    }

    override fun forget(attachmentIds: Set<String>) =
        synchronized(guard) {
            uploads.keys.removeAll { it.attachmentId in attachmentIds }
            Unit
        }

    override fun retain(attachmentIds: Set<String>) =
        synchronized(guard) {
            uploads.keys.retainAll { it.attachmentId in attachmentIds }
            Unit
        }

    override fun forgetSession(sessionId: String) =
        synchronized(guard) {
            retiredSessions.add(sessionId)
            uploads.keys.removeAll { it.sessionId == sessionId }
            requests.filterValues { it == sessionId }.keys.toList().forEach { it.cancel() }
        }

    override fun close() {
        synchronized(guard) {
            closed = true
            uploads.clear()
            retiredSessions.clear()
        }
        lifetime?.cancel()
        directory?.deleteRecursively()
    }

    private suspend fun importOwned(create: suspend () -> ComposerFile): ComposerFile {
        var created: ComposerFile? = null
        try {
            return withContext(dispatcher) { create().also { created = it } }
        } catch (error: CancellationException) {
            created?.let { file ->
                withContext(NonCancellable + dispatcher) { java.io.File(file.path).delete() }
            }
            throw error
        }
    }

    override suspend fun importImage(
        bytes: ByteArray,
        name: String?,
        mediaType: String?,
    ): ComposerFile = importOwned {
        if (bytes.size > AttachmentLimits.MAX_IMAGE_BYTES)
            throw AttachmentImportException("too_large")
        checkNotNull(directory).mkdirs()
        val id = UUID.randomUUID().toString()
        val target = File(directory, id)
        try {
            target.writeBytes(bytes)
            currentCoroutineContext().ensureActive()
            val type = AttachmentMetadata.imageMime(target) ?: mediaType
            if (type !in AttachmentLimits.imageTypes)
                throw AttachmentImportException("unsupported_image")
            ComposerFile(
                id,
                name?.takeIf(String::isNotBlank) ?: "image",
                type!!,
                (target).absolutePath,
                bytes.size.toLong(),
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    override suspend fun importFile(source: AttachmentSource, availableBytes: Long): ComposerFile =
        importOwned {
            val metadata = checkNotNull(metadata)(source)
            val name = attachmentFileName(metadata.name)
            val mime = metadata.mediaType ?: AttachmentMetadata.mimeOf(null, name)
            val limit =
                minOf(
                    availableBytes,
                    if (mime in AttachmentLimits.imageTypes) AttachmentLimits.MAX_IMAGE_BYTES
                    else AttachmentLimits.MAX_FILE_BYTES,
                )
            if (limit < 1) throw AttachmentImportException("too_large")
            checkNotNull(directory).mkdirs()
            val id = UUID.randomUUID().toString()
            val target = File(directory, id)
            try {
                val input =
                    checkNotNull(open)(source) ?: throw AttachmentImportException("unreadable")
                var bytes = 0L
                input.use { source ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > limit) throw AttachmentImportException("too_large")
                            out.write(buffer, 0, count)
                        }
                    }
                }
                ComposerFile(id, name, mime, (target).absolutePath, bytes)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }

    override suspend fun release(files: List<ComposerFile>) =
        withContext(NonCancellable + dispatcher) {
            files.forEach { java.io.File(it.path).delete() }
        }
}

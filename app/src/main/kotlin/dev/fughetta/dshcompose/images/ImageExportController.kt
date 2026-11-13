package dev.fughetta.dshcompose.images

import dev.fughetta.dshcompose.domain.images.ReplyImageException
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.markdown.ReplyImageRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

class StagedImage(
    val file: File,
    val name: String,
    val mime: String,
    private val cleanup: () -> Unit = { file.delete() },
) : AutoCloseable {
    override fun close() = cleanup()
}

/** The prepared file stays owned by this job while Android requests storage permission. */
class ImageExportController(
    private val scope: CoroutineScope,
    private val stage: suspend (ReplyImageRequest) -> StagedImage,
    private val save: suspend (StagedImage) -> Unit,
    private val share: suspend (StagedImage) -> ImageShareFile,
    private val hasPermission: () -> Boolean,
    private val legacyPermission: () -> Boolean,
    private val publish: (ImageExportUiState?) -> Unit,
) : AutoCloseable {
    private var state: ImageExportUiState? = null
    private var job: Job? = null
    private var permission: CompletableDeferred<Boolean>? = null
    private var promptId: String? = null
    private var shareId: String? = null

    fun start(request: ReplyImageRequest, operation: ImageExportOperation) {
        if (job?.isActive == true || promptId != null) return
        val id = UUID.randomUUID().toString()
        update(ImageExportUiState(id, operation))
        job = scope.launch {
            var image: StagedImage? = null
            try {
                withContext(Dispatchers.IO) { image = stage(request) }
                val prepared = image!!
                if (state?.id != id) return@launch
                update(state!!.copy(name = prepared.name))
                if (operation == ImageExportOperation.Save) {
                    var asked = false
                    if (legacyPermission() && !hasPermission()) {
                        awaitPermission(id)
                        asked = true
                    }
                    try {
                        withContext(Dispatchers.IO) { save(prepared) }
                    } catch (error: ReplyImageException) {
                        if (error.code != "image/permission" || asked || hasPermission())
                            throw error
                        awaitPermission(id)
                        withContext(Dispatchers.IO) { save(prepared) }
                    }
                    if (state?.id == id) update(state!!.copy(busy = false, saved = true))
                } else {
                    val file = withContext(Dispatchers.IO) { share(prepared) }
                    if (state?.id == id) update(state!!.copy(busy = false, share = file))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state?.id == id)
                    update(
                        state!!.copy(
                            busy = false,
                            needsPermission = false,
                            error =
                                (error as? ReplyImageException)?.code
                                    ?: if (operation == ImageExportOperation.Save) "image/save"
                                    else "image/share",
                        ),
                    )
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { image?.close() }
            }
        }
    }

    private suspend fun awaitPermission(id: String) {
        val result = CompletableDeferred<Boolean>()
        permission = result
        update(state!!.copy(needsPermission = true))
        try {
            if (!result.await()) throw ReplyImageException("image/permission")
            if (state?.id == id) update(state!!.copy(needsPermission = false))
        } finally {
            if (permission === result) permission = null
            result.cancel()
        }
    }

    fun claimPermission(id: String): Boolean {
        if (
            state?.id != id ||
                state?.needsPermission != true ||
                permission?.isCompleted != false ||
                promptId != null
        )
            return false
        promptId = id
        return true
    }

    fun permissionResult(granted: Boolean) {
        val id = promptId ?: return
        promptId = null
        if (state?.id == id) permission?.complete(granted)
    }

    fun claimShare(id: String): Boolean {
        if (state?.id != id || state?.share == null || shareId == id) return false
        shareId = id
        return true
    }

    fun shared(id: String, launched: Boolean) {
        if (state?.id == id && shareId == id)
            update(
                state!!.copy(
                    share = null,
                    shared = launched,
                    error = if (launched) null else "image/share",
                ),
            )
    }

    fun dismiss() {
        job?.cancel()
        permission?.cancel()
        update(null)
    }

    private fun update(value: ImageExportUiState?) {
        state = value
        publish(value)
    }

    override fun close() = dismiss()
}

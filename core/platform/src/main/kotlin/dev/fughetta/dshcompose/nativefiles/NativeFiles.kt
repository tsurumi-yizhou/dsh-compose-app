package dev.fughetta.dshcompose.nativefiles

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResult
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

data class NativeFileRequest(
    val owner: String,
    val maxFiles: Int,
    val maxBytes: Int,
    val maxTotalBytes: Int,
    val images: Boolean = false,
    val visual: Boolean = false,
    val own: Boolean = false,
)

/** Temporary, read-only handles for system-picked files. No storage permission or cache copy. */
class NativeFiles(private val context: Context, private val foreground: () -> Boolean) :
    AutoCloseable {
    private class Entry(val view: SelectedNativeFile, val uri: Uri)

    private class Selection(val request: NativeFileRequest, val budget: SelectedFileBudget) {
        val signal = CancellationSignal()
        val result = CompletableDeferred<List<SelectedNativeFile>?>()
        @Volatile var reader: SelectedFileReader? = null
        @Volatile var active = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private var selection: Selection? = null
    private var expiry: Job? = null
    private var waitingForPicker = false
    private var ioBusy = false
    private var closing = false
    private var closed = false

    /** Invoke from a foreground tap; [launch] is an Activity Result launcher. */
    suspend fun pick(
        request: NativeFileRequest,
        launch: (Intent) -> Unit,
    ): List<SelectedNativeFile>? =
        withContext(Dispatchers.Main.immediate) {
            if (closed || !foreground()) throw NativeFileException("unavailable")
            if (waitingForPicker || selection != null || ioBusy || closing)
                throw NativeFileException("busy")
            if (!request.owner.matches(Regex("^[A-Za-z0-9_-]{32}$")))
                throw NativeFileException("invalid")
            val budget =
                try {
                    SelectedFileBudget(
                        request.maxFiles,
                        request.maxBytes,
                        request.maxTotalBytes,
                        request.own,
                    )
                } catch (_: IllegalArgumentException) {
                    throw NativeFileException("invalid")
                }
            val own = Selection(request, budget)
            selection = own
            waitingForPicker = true
            expiry = scope.launch {
                delay(5 * 60 * 1000L)
                if (selection === own) cancelSelection()
            }
            try {
                try {
                    launch(pickerIntent(request))
                } catch (_: ActivityNotFoundException) {
                    launch(documentIntent(request))
                }
            } catch (_: Exception) {
                waitingForPicker = false
                cancelSelection()
                throw NativeFileException("unavailable")
            }
            try {
                own.result.await()
            } catch (error: CancellationException) {
                if (selection === own) cancelSelection()
                throw error
            }
        }

    fun acceptResult(result: ActivityResult) {
        waitingForPicker = false
        val own = selection ?: return
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            cancelSelection()
            return
        }
        val data = result.data!!
        val count = data.clipData?.itemCount ?: if (data.data != null) 1 else 0
        if (count !in 1..own.budget.maxFiles) {
            fail(own, NativeFileException("too-large"))
            return
        }
        val uris = (0 until count).map { data.clipData?.getItemAt(it)?.uri ?: data.data!! }
        scope.launch {
            try {
                val files =
                    io(own) {
                        val entries = uris.map { uri -> metadata(uri, own) }
                        own.budget.metadata(entries.map { it.view.bytes })
                        val byHandle = entries.associateBy { it.view.handle }
                        own.reader =
                            SelectedFileReader(own.budget, byHandle.keys) { handle ->
                                val descriptor =
                                    context.contentResolver.openAssetFileDescriptor(
                                        byHandle.getValue(handle).uri,
                                        "r",
                                        own.signal,
                                    ) ?: error("unreadable")
                                try {
                                    descriptor.createInputStream()
                                } catch (error: Exception) {
                                    descriptor.close()
                                    throw error
                                }
                            }
                        entries.map { it.view }
                    }
                own.result.complete(files)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NativeFileException) {
                fail(own, error)
            }
        }
    }

    suspend fun read(owner: String, handle: String): ByteArray? =
        withContext(Dispatchers.Main.immediate) {
            val own =
                selection?.takeIf { it.active && it.request.owner == owner }
                    ?: throw NativeFileException("cancelled")
            val reader = own.reader ?: throw NativeFileException("cancelled")
            try {
                io(own) { reader.read(handle) }
            } catch (error: NativeFileException) {
                if (error.code !in setOf("busy", "cancelled") && selection === own)
                    cancelSelection()
                throw error
            }
        }

    private fun metadata(uri: Uri, own: Selection): Entry {
        require(uri.scheme == "content")
        var name = "file"
        var size: Long? = null
        context.contentResolver
            .query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
                own.signal,
            )
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni)
                    if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si).takeIf { it >= 0 }
                }
            }
        val safe =
            name
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filter { it >= ' ' && it != '\u007f' }
                .take(128)
                .ifEmpty { "file" }
        val mime =
            context.contentResolver.getType(uri)?.takeIf { it.matches(MIME) }
                ?: "application/octet-stream"
        return Entry(SelectedNativeFile(UUID.randomUUID().toString(), safe, size, mime), uri)
    }

    private suspend fun <T> io(own: Selection, work: () -> T): T {
        if (ioBusy || closing) throw NativeFileException("busy")
        if (closed || selection !== own || !own.active) throw NativeFileException("cancelled")
        ioBusy = true
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                main.post { if (selection === own) cancelSelection() }
            }
            worker.execute {
                val result = runCatching(work)
                main.post {
                    ioBusy = false
                    if (!continuation.isActive) return@post
                    if (selection !== own || !own.active)
                        continuation.resumeWithException(NativeFileException("cancelled"))
                    else
                        result.fold(
                            continuation::resume,
                            { error ->
                                continuation.resumeWithException(
                                    if (error is NativeFileException) error
                                    else
                                        NativeFileException(
                                            if (error.message == "too-large") "too-large"
                                            else "failed",
                                        ),
                                )
                            },
                        )
                }
            }
        }
    }

    fun cancel(owner: String) {
        if (selection?.request?.owner == owner) cancelSelection()
    }

    fun detach() {
        cancelSelection()
    }

    private fun fail(own: Selection, error: NativeFileException) {
        own.result.completeExceptionally(error)
        if (selection === own) cancelSelection()
    }

    private fun cancelSelection() {
        expiry?.cancel()
        expiry = null
        val own = selection ?: return
        selection = null
        own.active = false
        own.result.complete(null)
        own.signal.cancel()
        closing = true
        worker.execute {
            own.reader?.close()
            own.reader = null
            main.post { closing = false }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelSelection()
        scope.cancel()
        worker.shutdown()
    }

    private fun documentIntent(request: NativeFileRequest) =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (request.images && !request.visual) "image/*" else "*/*"
            if (request.visual) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.maxFiles > 1)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun pickerIntent(request: NativeFileRequest): Intent {
        if (!(request.images || request.visual) || Build.VERSION.SDK_INT < 33)
            return documentIntent(request)
        return Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            if (!request.visual) type = "image/*"
            val count = minOf(request.maxFiles, MediaStore.getPickImagesMaxLimit())
            if (count > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, count)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        val MIME =
            Regex("^[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]{0,63}$")
    }
}

package dev.fughetta.dshcompose.images

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.fughetta.dshcompose.domain.images.MAX_REPLY_IMAGE_BYTES
import dev.fughetta.dshcompose.domain.images.ReplyImageException
import dev.fughetta.dshcompose.domain.images.ReplyImageSource
import dev.fughetta.dshcompose.domain.images.replyImageSource
import dev.fughetta.dshcompose.domain.media.MediaRepository
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.media.MediaExports
import dev.fughetta.dshcompose.ui.ImageShareFile
import dev.fughetta.dshcompose.ui.markdown.ReplyImageRequest
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NativeImageExports(
    private val context: Context,
    private val downloads: MediaRepository,
    private val loader: ImageLoader,
    private val generation: () -> Long,
    private val files: () -> dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository?,
) {
    private val staging = File(context.cacheDir, "image-export")
    private val housekeeping = Mutex()
    private var cleaned = false

    suspend fun stage(request: ReplyImageRequest): StagedImage {
        housekeeping.withLock {
            if (!cleaned) {
                staging.mkdirs()
                staging.listFiles().orEmpty().forEach { it.deleteRecursively() }
                cleaned = true
            }
        }
        if (request.generation != generation())
            throw ReplyImageException("image/connection-changed")
        val owner = files()
        val token = owner?.generation
        val source = replyImageSource(request.address)
        val directory = File(staging, UUID.randomUUID().toString()).also { it.mkdirs() }
        var handedOff = false
        try {
            var file = File(directory, "source")
            try {
                file.outputStream().use { output ->
                    downloads.copyImage(source, output::write) { path, stream, limit ->
                        (owner ?: throw ReplyImageException("image/unavailable")).copy(
                            request.sessionId,
                            path,
                            stream,
                            maximumBytes = limit,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ReplyImageException) {
                throw error
            } catch (error: Exception) {
                throw ReplyImageException(
                    when (operationErrorCode(error)) {
                        "workspace-file/too-large" -> "image/too-large"
                        "workspace-file/changed" -> "image/changed"
                        else -> "image/download"
                    },
                )
            }
            if (
                request.generation != generation() ||
                    source is ReplyImageSource.Workspace && files()?.generation != token
            )
                throw ReplyImageException("image/connection-changed")
            var mime = downloads.imageMime(file.absolutePath)
            if (mime == null) {
                val decoded =
                    loader.execute(
                        ImageRequest.Builder(context)
                            .data(file)
                            .size(4096)
                            .allowHardware(false)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    ) as? SuccessResult ?: throw ReplyImageException("image/unsupported")
                val image = decoded.image
                val factor = minOf(1.0, 4096.0 / maxOf(image.width, image.height).coerceAtLeast(1))
                val bitmap =
                    image.toBitmap(
                        (image.width * factor).toInt().coerceAtLeast(1),
                        (image.height * factor).toInt().coerceAtLeast(1),
                    )
                file = File(directory, "converted.png")
                try {
                    file.outputStream().use { output ->
                        if (
                            !bitmap.compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                LimitedImageOutput(output),
                            )
                        )
                            throw ReplyImageException("image/unsupported")
                    }
                } finally {
                    bitmap.recycle()
                }
                mime = "image/png"
            }
            val name =
                GalleryImages.name(
                    when (source) {
                        is ReplyImageSource.Workspace -> source.path
                        is ReplyImageSource.Web -> URI(source.address).path.orEmpty()
                        else -> ""
                    },
                    mime,
                )
            currentCoroutineContext().ensureActive()
            if (request.generation != generation())
                throw ReplyImageException("image/connection-changed")
            return StagedImage(file, name, mime) { directory.deleteRecursively() }
                .also { handedOff = true }
        } finally {
            if (!handedOff) directory.deleteRecursively()
        }
    }

    suspend fun save(image: StagedImage) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, image.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, image.mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DSH Compose")
                        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val uri =
                    resolver.insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values,
                    ) ?: throw IOException("Image destination unavailable")
                try {
                    val transfer = currentCoroutineContext()
                    (resolver.openOutputStream(uri)
                            ?: throw IOException("Image destination unavailable"))
                        .use { copy(image.file, it, transfer) }
                    transfer.ensureActive()
                    if (
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null,
                        ) != 1
                    )
                        throw IOException("Image publication failed")
                } catch (error: Throwable) {
                    try {
                        resolver.delete(uri, null, null)
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                    throw error
                }
            } else {
                val directory =
                    File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES,
                            ),
                            "DSH Compose",
                        )
                        .also { it.mkdirs() }
                val transfer = currentCoroutineContext()
                val saved =
                    MediaExports.writeUnique(directory, image.name) {
                        copy(image.file, it, transfer)
                    }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(saved.absolutePath),
                    arrayOf(image.mime),
                    null,
                )
            }
        } catch (_: SecurityException) {
            throw ReplyImageException("image/permission")
        }
    }

    fun share(image: StagedImage): ImageShareFile {
        val file = GalleryImages.shareCopy(image, File(context.cacheDir, "reply-share"))
        return ImageShareFile(file.path, image.mime, image.name)
    }

    private fun copy(source: File, output: OutputStream, transfer: CoroutineContext) {
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                transfer.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }

    private class LimitedImageOutput(output: OutputStream) : FilterOutputStream(output) {
        var bytes = 0L

        override fun write(value: Int) {
            if (++bytes > MAX_REPLY_IMAGE_BYTES) throw ReplyImageException("image/too-large")
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bytes += length
            if (bytes > MAX_REPLY_IMAGE_BYTES) throw ReplyImageException("image/too-large")
            out.write(buffer, offset, length)
        }
    }
}

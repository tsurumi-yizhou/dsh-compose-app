package dev.fughetta.dshcompose.images

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.svg.SvgDecoder
import dev.fughetta.dshcompose.domain.images.ReplyImageException
import dev.fughetta.dshcompose.domain.images.ReplyImageSource
import dev.fughetta.dshcompose.domain.images.replyImageSource
import dev.fughetta.dshcompose.domain.media.MediaRepository
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.ui.markdown.ReplyImageRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toOkioPath

fun replyImageLoader(
    context: Context,
    downloads: MediaRepository,
    generation: () -> Long,
    files: () -> WorkspaceFilesRepository?,
): ImageLoader {
    val cache = File(context.cacheDir, "reply-images")
    val housekeeping = Mutex()
    var cleaned = false
    return ImageLoader.Builder(context)
        .fetcherCoroutineContext(Dispatchers.IO)
        .diskCache(null)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(24L * 1024 * 1024).build() }
        .components {
            add(SvgDecoder.Factory())
            add(
                Keyer<ReplyImageRequest> { data, _ ->
                    if (replyImageSource(data.address) is ReplyImageSource.Web)
                        "${data.generation}:${data.sessionId}:${data.retry}:${data.address}"
                    else null
                },
            )
            add(
                object : Fetcher.Factory<ReplyImageRequest> {
                    override fun create(
                        data: ReplyImageRequest,
                        options: Options,
                        imageLoader: ImageLoader,
                    ): Fetcher = Fetcher {
                        // Coil invokes fetch on its IO context and owns the returned source
                        // immediately.
                        if (generation() != data.generation)
                            throw ReplyImageException("image/connection-changed")
                        val owner = files()
                        val token = owner?.generation
                        val image = replyImageSource(data.address)
                        housekeeping.withLock {
                            if (!cleaned) {
                                cache.mkdirs()
                                cache.listFiles().orEmpty().forEach { it.delete() }
                                cleaned = true
                            }
                        }
                        val file = File.createTempFile("image-", ".tmp", cache)
                        var handedOff = false
                        try {
                            val mime =
                                file.outputStream().use { output ->
                                    downloads.copyImage(image, output::write) { path, stream, limit
                                        ->
                                        val engine =
                                            owner ?: throw ReplyImageException("image/unavailable")
                                        engine.copy(
                                            data.sessionId,
                                            path,
                                            stream,
                                            maximumBytes = limit,
                                        )
                                    }
                                }
                            currentCoroutineContext().ensureActive()
                            if (
                                generation() != data.generation ||
                                    image is ReplyImageSource.Workspace &&
                                        files()?.generation != token
                            )
                                throw ReplyImageException("image/connection-changed")
                            val source =
                                ImageSource(
                                    file.toOkioPath(),
                                    FileSystem.SYSTEM,
                                    closeable = AutoCloseable { file.delete() },
                                )
                            SourceFetchResult(source, mime, DataSource.NETWORK).also {
                                handedOff = true
                            }
                        } finally {
                            if (!handedOff) file.delete()
                        }
                    }
                },
            )
        }
        .build()
}

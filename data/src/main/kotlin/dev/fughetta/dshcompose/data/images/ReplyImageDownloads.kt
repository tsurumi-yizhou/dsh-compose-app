package dev.fughetta.dshcompose.data.images

import dev.fughetta.dshcompose.domain.images.MAX_REPLY_IMAGE_BYTES
import dev.fughetta.dshcompose.domain.images.ReplyImageException
import dev.fughetta.dshcompose.domain.images.ReplyImageSource
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The web client deliberately has no access to the engine connection or its cookies. */
class ReplyImageDownloads(
    engine: HttpClientEngine? = null,
    private val maximumBytes: Long = MAX_REPLY_IMAGE_BYTES,
) : AutoCloseable {
    private val http =
        if (engine == null) HttpClient(CIO) { replyImageConfiguration() }
        else HttpClient(engine) { replyImageConfiguration() }
    private val transfers = Semaphore(3)

    suspend fun copy(
        source: ReplyImageSource,
        destination: OutputStream,
        workspace: suspend (String, OutputStream, Long) -> Unit,
    ): String? = transfers.withPermit {
        withContext(Dispatchers.IO) {
            when (source) {
                is ReplyImageSource.Workspace -> {
                    workspace(source.path, destination, maximumBytes)
                    null
                }
                is ReplyImageSource.Web ->
                    http.prepareGet(source.address).execute { response ->
                        if (response.status.value !in 200..299)
                            throw ReplyImageException("image/http-error")
                        if ((response.contentLength() ?: 0) > maximumBytes)
                            throw ReplyImageException("image/too-large")
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            received += count
                            if (received > maximumBytes)
                                throw ReplyImageException("image/too-large")
                            destination.write(buffer, 0, count)
                        }
                        response.contentType()?.withoutParameters()?.toString()
                    }
                is ReplyImageSource.Data -> copyData(source.address, destination)
                ReplyImageSource.Unsupported -> throw ReplyImageException("image/unsupported")
            }
        }
    }

    private suspend fun copyData(address: String, destination: OutputStream): String {
        val comma = address.indexOf(',')
        if (comma < 0) throw ReplyImageException("image/unsupported")
        val header = address.substring(5, comma)
        val mime = header.substringBefore(';').lowercase()
        if (!mime.startsWith("image/")) throw ReplyImageException("image/unsupported")
        var received = 0L
        fun write(bytes: ByteArray, count: Int) {
            received += count
            if (received > maximumBytes) throw ReplyImageException("image/too-large")
            destination.write(bytes, 0, count)
        }
        try {
            if (header.endsWith(";base64", true)) {
                val raw =
                    object : InputStream() {
                        var at = comma + 1

                        override fun read(): Int =
                            if (at == address.length) -1
                            else
                                address[at++].code.also {
                                    if (it > 127) throw ReplyImageException("image/invalid-data")
                                }
                    }
                Base64.getDecoder().wrap(raw).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count =
                            try {
                                stream.read(buffer)
                            } catch (_: java.io.IOException) {
                                throw ReplyImageException("image/invalid-data")
                            }
                        if (count < 0) break
                        write(buffer, count)
                    }
                }
            } else {
                val buffer = ByteArray(64 * 1024)
                var size = 0
                var at = comma + 1
                while (at < address.length) {
                    if (size > buffer.size - 4) {
                        currentCoroutineContext().ensureActive()
                        write(buffer, size)
                        size = 0
                    }
                    if (address[at] == '%') {
                        if (at + 2 >= address.length)
                            throw ReplyImageException("image/invalid-data")
                        val high =
                            address[at + 1].digitToIntOrNull(16)
                                ?: throw ReplyImageException("image/invalid-data")
                        val low =
                            address[at + 2].digitToIntOrNull(16)
                                ?: throw ReplyImageException("image/invalid-data")
                        buffer[size++] = ((high shl 4) or low).toByte()
                        at += 3
                    } else if (address[at].code < 128) {
                        buffer[size++] = address[at++].code.toByte()
                    } else {
                        val code = address.codePointAt(at)
                        val bytes = String(Character.toChars(code)).toByteArray(Charsets.UTF_8)
                        bytes.copyInto(buffer, size)
                        size += bytes.size
                        at += Character.charCount(code)
                    }
                }
                write(buffer, size)
            }
        } catch (_: IllegalArgumentException) {
            throw ReplyImageException("image/invalid-data")
        }
        return mime
    }

    override fun close() = http.close()
}

private fun HttpClientConfig<*>.replyImageConfiguration() {
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    install(ContentEncoding) {
        gzip()
        deflate()
    }
}

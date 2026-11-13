package dev.fughetta.dshcompose.protocol

import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.OutputStream
import kotlinx.coroutines.*

val ProtocolClient.canExportSessionLog: Boolean
    get() = connection.supportsSessionExport

suspend fun ProtocolClient.exportSessionLog(
    sessionId: String,
    destination: OutputStream,
    onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
): Long = connection.copySessionArchive(sessionId, destination, onProgress)

internal suspend fun HostConnection.copyHttpSessionArchive(
    sessionId: String,
    destination: OutputStream,
    onProgress: suspend (Long, Long?) -> Unit,
): Long =
    withContext(Dispatchers.IO) {
        val download =
            request("/compose/sessions/export").apply {
                url.parameters.append("sessionId", sessionId)
                url.parameters.append("includeDescendants", "true")
                timeout {
                    requestTimeoutMillis = 300_000
                    socketTimeoutMillis = 300_000
                }
            }
        exchange(download) { response ->
            if (response.status.value != 200)
                throw HostRemoteException("session-export/http-${response.status.value}")
            val total = response.contentLength()?.takeIf { it >= 0 }
            val source = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            onProgress(0, total)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.readAvailable(buffer)
                if (count < 0) break
                destination.write(buffer, 0, count)
                bytes += count
                onProgress(bytes, total)
            }
            currentCoroutineContext().ensureActive()
            if (total != null && total != bytes)
                throw HostProtocolException("Incomplete session archive")
            bytes
        }
    }

package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.attachments.AttachmentReference
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.cio.readChannel
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class FileReceipt(val receiptId: String, val attachment: JsonObject)

suspend fun ProtocolClient.uploadFile(
    sessionId: String,
    file: File,
    name: String = file.name,
    onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
): FileReceipt = connection.uploadFile(sessionId, file, name, onProgress)

/** Stream from disk without a base64 copy or an automatic retry. */
internal suspend fun HostConnection.uploadHttpFile(
    sessionId: String,
    file: File,
    name: String = file.name,
    onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
): FileReceipt =
    withContext(Dispatchers.IO) {
        require(file.isFile)
        val id = UUID.randomUUID().toString()
        val sentAt = System.currentTimeMillis()
        val channel = file.readChannel()
        try {
            val request =
                request("/compose/files/upload").apply {
                    method = HttpMethod.Post
                    url.parameters.append("sessionId", sessionId)
                    url.parameters.append("name", name)
                    contentType(ContentType.Application.OctetStream)
                    header(HttpHeaders.ContentLength, file.length())
                    header("X-Compose-Request-Id", id)
                    header("X-Compose-Sent-At", sentAt)
                    timeout { requestTimeoutMillis = 600_000 }
                    setBody(channel)
                    onUpload { sent, total -> onProgress(sent, total) }
                }
            exchange(request) { response ->
                if (response.status.value != 200) throw HostHttpException(response.status.value)
                val result = response.jsonObject(64 * 1024)
                if (result.string("id") != id)
                    throw HostProtocolException("Upload request id mismatch")
                if (result.boolean("ok") != true) throw remoteError(result["error"])
                val value =
                    result["value"]?.objectValue()
                        ?: throw HostProtocolException("Upload returned no receipt")
                FileReceipt(
                    value.requiredString("receiptId"),
                    value["file"]?.objectValue()
                        ?: throw HostProtocolException("Upload returned no file"),
                )
            }
        } finally {
            channel.cancel(null)
        }
    }

fun promptImage(mediaType: String, bytes: ByteArray, name: String? = null): JsonObject {
    require(mediaType in setOf("image/png", "image/jpeg", "image/webp", "image/gif"))
    require(bytes.size <= 20 * 1024 * 1024) { "Image exceeds the prompt byte limit" }
    return buildJsonObject {
        put("type", "image")
        put("mediaType", mediaType)
        put("data", Base64.getEncoder().encodeToString(bytes))
        name?.let { put("name", it) }
    }
}

/** The complete durable reference is required; attachment ids alone do not identify an image. */
suspend fun ProtocolClient.readImage(sessionId: String, reference: AttachmentReference): ByteArray {
    val value =
        connection
            .call(
                "session/attachment",
                buildJsonObject {
                    putJsonObject("request") {
                        put("sessionId", sessionId)
                        put(
                            "attachment",
                            (reference.payload as? JsonObject)
                                ?: throw HostProtocolException("Invalid image reference"),
                        )
                    }
                },
            )
            .objectValue()
    val encoded = value.requiredString("data")
    if (encoded.length > 28 * 1024 * 1024)
        throw HostProtocolException("Image exceeds the response byte limit")
    val bytes =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw HostProtocolException("Invalid image data")
        }
    if (bytes.size > 20 * 1024 * 1024)
        throw HostProtocolException("Image exceeds the response byte limit")
    return bytes
}

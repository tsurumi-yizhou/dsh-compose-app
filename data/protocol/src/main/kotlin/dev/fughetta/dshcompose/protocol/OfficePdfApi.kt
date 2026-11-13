package dev.fughetta.dshcompose.protocol

import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class OfficePdfResult(val bytes: Long, val missingFonts: PersistentList<String>)

suspend fun ProtocolClient.renderOfficePdf(
    sessionId: String,
    path: String,
    destination: OutputStream,
    maximumBytes: Long = 64L * 1024 * 1024,
): OfficePdfResult = connection.renderOfficePdf(sessionId, path, destination, maximumBytes)

/** HTTP Office results carry a PDF attachment; stream it without a whole-document byte array. */
internal suspend fun HostConnection.renderHttpOfficePdf(
    sessionId: String,
    path: String,
    destination: OutputStream,
    maximumBytes: Long = 64L * 1024 * 1024,
): OfficePdfResult =
    withContext(Dispatchers.IO) {
        require(maximumBytes > 0)
        val id = UUID.randomUUID().toString()
        val request =
            request("/compose/files/pdf").apply {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                timeout {
                    requestTimeoutMillis = 180_000
                    socketTimeoutMillis = 180_000
                }
                setBody(
                    buildJsonObject {
                        put("type", "client-request")
                        put("rpcId", id)
                        put("method", "officeToPdf/render")
                        putJsonObject("payload") {
                            putJsonObject("args") {
                                put("workspaceFileScopeId", sessionId)
                                put("path", path)
                                put("priority", "foreground")
                            }
                        }
                    }
                        .toString(),
                )
            }
        exchange(request) { response ->
            if (response.status.value != 200) throw HostHttpException(response.status.value)
            val type =
                response.contentType()
                    ?: throw HostProtocolException("Missing Office response type")
            if (type.withoutParameters() == ContentType.Application.Json) {
                readRpcEnvelope(response.jsonObject(65536), id)
                throw HostProtocolException("Missing PDF attachment")
            }
            if (type.withoutParameters() != ContentType.MultiPart.FormData)
                throw HostProtocolException("Expected PDF multipart response")
            coroutineScope {
                // Enforce budgets while copying so parser cleanup cannot replace the stable size
                // error.
                val parts =
                    parseMultipart(
                        response.bodyAsChannel(),
                        type.toString(),
                        response.contentLength(),
                        Long.MAX_VALUE,
                    )
                var metadata: JsonObject? = null
                var attachmentName: String? = null
                var received = 0L
                try {
                    for (part in parts) {
                        var headers: HttpHeadersMap? = null
                        var failed = false
                        try {
                            if (part !is MultipartEvent.MultipartPart) continue
                            headers = part.headers.await()
                            val disposition =
                                headers[HttpHeaders.ContentDisposition]?.toString()
                                    ?: throw HostProtocolException("Missing multipart disposition")
                            val name =
                                Regex("(?:^|;)\\s*name=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                                    .find(disposition)
                                    ?.groupValues
                                    ?.get(1)
                                    ?: throw HostProtocolException("Missing multipart name")
                            val buffer = ByteArray(64 * 1024)
                            if (name == "metadata") {
                                if (metadata != null)
                                    throw HostProtocolException("Duplicate PDF metadata")
                                val bytes = ByteArrayOutputStream()
                                while (true) {
                                    val count = part.body.readAvailable(buffer)
                                    if (count < 0) break
                                    if (bytes.size() + count > 65536)
                                        throw HostProtocolException(
                                            "PDF metadata exceeds byte limit",
                                        )
                                    bytes.write(buffer, 0, count)
                                }
                                metadata = parseObject(bytes.toString(Charsets.UTF_8))
                                readRpcEnvelope(metadata, id)
                            } else {
                                if (attachmentName != null)
                                    throw HostProtocolException("Expected one PDF attachment")
                                attachmentName = name
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = part.body.readAvailable(buffer)
                                    if (count < 0) break
                                    if (received + count > maximumBytes)
                                        throw HostRemoteException("workspace-file/too-large")
                                    destination.write(buffer, 0, count)
                                    received += count
                                }
                            }
                        } catch (error: Throwable) {
                            failed = true
                            throw error
                        } finally {
                            if (failed && part is MultipartEvent.MultipartPart) {
                                headers?.release()
                                part.body.cancel()
                                parts.cancel()
                            } else part.releaseSuspend()
                        }
                    }
                } finally {
                    parts.cancel()
                }
                val envelope = metadata ?: throw HostProtocolException("Missing PDF metadata")
                val value = readRpcEnvelope(envelope, id).objectValue()
                val attachment =
                    (envelope["attachments"] as? JsonArray)?.singleOrNull()?.objectValue()
                        ?: throw HostProtocolException("Missing PDF attachment table")
                if (
                    attachment.string("codec") != "bytes" ||
                        attachment.string("part") != attachmentName ||
                        attachment["path"] != JsonArray(listOf(JsonPrimitive("data"))) ||
                        value["data"] != JsonNull ||
                        received == 0L
                )
                    throw HostProtocolException("Invalid PDF attachment")
                OfficePdfResult(
                    received,
                    (value["missingFonts"] as? JsonArray)
                        .orEmpty()
                        .map { it.jsonPrimitive.content }
                        .toPersistentList(),
                )
            }
        }
    }

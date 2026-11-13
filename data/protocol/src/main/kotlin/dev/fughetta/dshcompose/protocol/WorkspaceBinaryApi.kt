// Portions derived from DeepSeek Harness, MIT, Copyright (c) 2026 DeepSeek. See
// THIRD_PARTY_NOTICES.md.
package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.workspaces.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceByteWindow
import io.ktor.client.request.*
import io.ktor.http.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal suspend fun HostConnection.readHttpWorkspaceBytes(
    sessionId: String,
    path: String,
    offset: Long,
    length: Int = 1024 * 1024,
): WorkspaceByteWindow =
    withContext(Dispatchers.IO) {
        require(offset >= 0 && length in 1..2 * 1024 * 1024)
        val id = UUID.randomUUID().toString()
        val request =
            request("/compose/files/read").apply {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("type", "client-request")
                        put("rpcId", id)
                        put("method", "workspaceFiles/readBytes")
                        putJsonObject("payload") {
                            putJsonObject("args") {
                                put("workspaceFileScopeId", sessionId)
                                put("path", path)
                                putJsonObject("options") {
                                    putJsonObject("range") {
                                        put("offset", offset)
                                        put("length", length)
                                    }
                                }
                            }
                        }
                    }
                        .toString(),
                )
            }
        exchange(request) { response ->
            if (response.status.value != 200) throw HostHttpException(response.status.value)
            val contentType =
                response.contentType()
                    ?: throw HostProtocolException("Missing binary response type")
            val maxBody = (length.toLong() * 4 / 3 + 65536)
            val body = response.boundedBytes(maxBody)
            val value: JsonObject
            val bytes: ByteArray
            if (contentType.withoutParameters() == ContentType.MultiPart.FormData) {
                val fields =
                    binaryFields(
                        body,
                        contentType.parameter("boundary")
                            ?: throw HostProtocolException("Missing multipart boundary"),
                    )
                val metadata =
                    fields.remove("metadata")
                        ?: throw HostProtocolException("Missing binary metadata")
                val envelope = parseObject(metadata.toString(Charsets.UTF_8))
                value = readRpcEnvelope(envelope, id).objectValue()
                val attachment =
                    (envelope["attachments"] as? JsonArray)?.singleOrNull()?.objectValue()
                        ?: throw HostProtocolException("Expected one byte attachment")
                if (
                    attachment.string("codec") != "bytes" ||
                        attachment["path"] != JsonArray(listOf(JsonPrimitive("data"))) ||
                        value["data"] != JsonNull
                )
                    throw HostProtocolException("Invalid byte attachment")
                bytes =
                    fields.remove(attachment.requiredString("part"))
                        ?: throw HostProtocolException("Missing byte attachment")
                if (fields.isNotEmpty()) throw HostProtocolException("Unclaimed binary fields")
            } else if (contentType.withoutParameters() == ContentType.Application.Json) {
                value =
                    readRpcEnvelope(parseObject(body.toString(Charsets.UTF_8)), id).objectValue()
                bytes =
                    try {
                        Base64.getDecoder()
                            .decode(
                                value.string("data")
                                    ?: throw HostProtocolException("Missing binary data"),
                            )
                    } catch (_: IllegalArgumentException) {
                        throw HostProtocolException("Invalid binary data")
                    }
            } else throw HostProtocolException("Unexpected binary response type")
            if (bytes.size > length)
                throw HostProtocolException("Byte window exceeds requested length")
            WorkspaceByteWindow(
                bytes,
                value.requiredLong("offset"),
                value.boolean("eof") == true,
                value.requiredString("version"),
            )
        }
    }

private fun binaryFields(body: ByteArray, boundary: String): MutableMap<String, ByteArray> {
    if (boundary.isEmpty() || boundary.length > 200 || boundary.any { it == '\r' || it == '\n' })
        throw HostProtocolException("Invalid multipart boundary")
    val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
    val nextDelimiter = "\r\n--$boundary".toByteArray(Charsets.US_ASCII)
    fun index(needle: ByteArray, from: Int): Int {
        outer@ for (i in from..body.size - needle.size) {
            for (j in needle.indices) if (body[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
    val fields = linkedMapOf<String, ByteArray>()
    var at = index(delimiter, 0)
    if (at < 0) throw HostProtocolException("Missing multipart boundary")
    while (true) {
        val start = at + delimiter.size
        if (start + 1 >= body.size) throw HostProtocolException("Unclosed multipart body")
        if (body[start] == 45.toByte() && body[start + 1] == 45.toByte()) return fields
        if (body[start] != 13.toByte() || body[start + 1] != 10.toByte())
            throw HostProtocolException("Invalid multipart separator")
        val headerEnd = index(byteArrayOf(13, 10, 13, 10), start + 2)
        if (headerEnd < 0) throw HostProtocolException("Missing multipart headers")
        val headers = body.copyOfRange(start + 2, headerEnd).toString(Charsets.UTF_8).lineSequence()
        val disposition =
            headers.firstOrNull { it.startsWith("Content-Disposition:", true) }?.substringAfter(':')
                ?: throw HostProtocolException("Missing multipart disposition")
        val name =
            Regex("(?:^|;)\\s*name=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                .find(disposition)
                ?.groupValues
                ?.get(1) ?: throw HostProtocolException("Missing multipart name")
        var next = index(nextDelimiter, headerEnd + 4)
        while (next >= 0) {
            val end = next + nextDelimiter.size
            if (
                end + 1 < body.size &&
                    (body[end] == 13.toByte() && body[end + 1] == 10.toByte() ||
                        body[end] == 45.toByte() && body[end + 1] == 45.toByte())
            )
                break
            next = index(nextDelimiter, end)
        }
        if (next < 0 || fields.put(name, body.copyOfRange(headerEnd + 4, next)) != null)
            throw HostProtocolException("Invalid multipart field")
        at = next + 2
    }
}

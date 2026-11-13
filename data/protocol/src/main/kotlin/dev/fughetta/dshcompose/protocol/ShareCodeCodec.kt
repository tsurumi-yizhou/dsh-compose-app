package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import java.io.*
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.*
import kotlinx.serialization.json.*

enum class ShareKind {
    plugin,
    profile,
    conversation,
}

data class ShareEnvelope(val code: String, val name: String? = null, val assets: JsonObject? = null)

/** The existing .dshcompose wire format. The digest is part of the payload protocol. */
object ShareCodeCodec {
    const val mime = "application/vnd.dshcompose+json"
    const val maxFileBytes = 4 * 1024 * 1024
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun checksum(bytes: ByteArray): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(6))

    fun assetId(bytes: ByteArray): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(9))

    fun encode(body: JsonObject): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val limit = if (body.npString("kind") == "conversation") 16 * 1024 * 1024 else 1024 * 1024
        if (bytes.size > limit) throw ShareFormatException("share/too-large")
        val packed = ByteArrayOutputStream()
        val compressor = Deflater(9, true)
        try {
            DeflaterOutputStream(packed, compressor).use { it.write(bytes) }
        } finally {
            compressor.end()
        }
        return b64.encodeToString(packed.toByteArray()) + "." + checksum(bytes)
    }

    fun decode(code: String): JsonObject {
        val text = code.trim()
        if (text.length > 6 * 1024 * 1024) throw ShareFormatException("share/too-large")
        val at = text.indexOf('.')
        if (
            at <= 0 ||
                at != text.lastIndexOf('.') ||
                !text.substring(at + 1).matches(Regex("[A-Za-z0-9_-]{8}"))
        )
            throw ShareFormatException("share/invalid-code")
        val data = text.substring(0, at)
        if (!data.matches(Regex("[A-Za-z0-9_-]+"))) throw ShareFormatException("share/invalid-code")
        val inflater = Inflater(true)
        val bytes =
            try {
                InflaterInputStream(
                        ByteArrayInputStream(Base64.getUrlDecoder().decode(data)),
                        inflater,
                    )
                    .use { boundedShareRead(it, 16 * 1024 * 1024) }
            } catch (error: ShareFormatException) {
                throw error
            } catch (_: Exception) {
                throw ShareFormatException("share/invalid-code")
            } finally {
                inflater.end()
            }
        if (checksum(bytes) != text.substring(at + 1))
            throw ShareFormatException("share/damaged-code")
        val body =
            try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw ShareFormatException("share/invalid-body")
        if (
            body.npString("kind") != "conversation" &&
                (bytes.size > 1024 * 1024 || text.length > 1024 * 1024)
        )
            throw ShareFormatException("share/too-large")
        return body
    }

    fun envelope(text: String): ShareEnvelope {
        val value = text.trim()
        if (!value.startsWith('{')) return ShareEnvelope(value)
        if (text.toByteArray(Charsets.UTF_8).size > maxFileBytes)
            throw ShareFormatException("share/too-large")
        val body =
            try {
                Json.parseToJsonElement(value) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw ShareFormatException("share/invalid-file")
        if ((body["dshcompose"] as? JsonPrimitive)?.intOrNull == null)
            throw ShareFormatException("share/invalid-file")
        return ShareEnvelope(
            body.npString("code") ?: throw ShareFormatException("share/invalid-file"),
            body.npString("name"),
            body["assets"] as? JsonObject,
        )
    }

    fun file(body: JsonObject, assets: JsonObject? = null, code: String? = null): String {
        val text =
            buildJsonObject {
                put("dshcompose", if (body.npString("kind") == "conversation") 2 else 1)
                put(
                    "kind",
                    body.npString("kind") ?: throw ShareFormatException("share/invalid-body"),
                )
                (body.npString("name") ?: body.npString("title"))?.let { put("name", it) }
                put("code", code ?: encode(body))
                assets?.takeIf { it.isNotEmpty() }?.let { put("assets", it) }
            }
                .toString() + "\n"
        if (text.toByteArray(Charsets.UTF_8).size > maxFileBytes)
            throw ShareFormatException("share/too-large")
        return text
    }
}

internal fun boundedShareRead(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (out.size().toLong() + count > limit) throw ShareFormatException("share/too-large")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.MediaKind
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*

suspend fun ProtocolClient.readMediaWorks(
    cursor: String? = null,
    kind: MediaKind? = null,
    limit: Int = 40,
): MediaWorksPage {
    require(limit in 1..100)
    val connection =
        connection as? HostConnection ?: throw HostRemoteException("media/works-unreachable")
    val req =
        connection.request("/compose/extensions/v1/media/works").apply {
            url.parameters.append("limit", limit.toString())
            cursor?.let { url.parameters.append("cursor", it) }
            kind?.let { url.parameters.append("kind", it.wire) }
        }
    return connection.exchange(req) { response ->
        val raw = response.jsonObject(2L * 1024 * 1024)
        if (response.status.value != 200)
            throw HostRemoteException(
                (raw["error"] as? JsonObject)?.npString("code") ?: "media/list-failed",
            )
        MediaWorksPage(
            raw.npObjects("items").mapNotNull { value ->
                val media = decodeGeneratedMedia(value["media"]) ?: return@mapNotNull null
                MediaWork(
                    value.npString("key") ?: throw HostProtocolException("Missing work key"),
                    value.npString("sessionId")
                        ?: throw HostProtocolException("Missing work session"),
                    value.miLong("createdAt") ?: 0,
                    value.npString("cwd"),
                    media,
                )
            },
            raw.npString("next"),
        )
    }
}

/**
 * A confirmation names exactly the record and files displayed. Only the Host can confirm removal.
 */
suspend fun ProtocolClient.deleteMediaWork(work: MediaWork) {
    require(!work.local)
    val connection =
        connection as? HostConnection ?: throw HostRemoteException("media/works-unreachable")
    val body = buildJsonObject {
        put("key", work.key)
        put("files", JsonArray(work.media.paths.map(::JsonPrimitive)))
        put("removeIndex", true)
    }
    val req =
        connection.request("/compose/extensions/v1/media/works/delete").apply {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    connection.exchange(req) { response ->
        val raw = response.jsonObject(16384)
        if (response.status.value != 200)
            throw HostRemoteException(
                (raw["error"] as? JsonObject)?.npString("code") ?: "media/delete-failed",
                details = raw["error"],
            )
        if (
            !raw.npBool("deleted") ||
                !raw.npBool("removedIndex") ||
                raw.npString("key") != work.key ||
                raw.npStrings("files").toSet() != work.media.paths
        )
            throw HostProtocolException("Host did not confirm work removal")
    }
}

package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import dev.fughetta.dshcompose.domain.sharing.SharedPlugin
import dev.fughetta.dshcompose.domain.sharing.SharedPluginSource
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class SharedNpmFacts(
    val integrity: String?,
    val publisher: String?,
    val size: Long?,
    val description: String?,
)

/** Public registry/Gist transport never borrows an engine login. */
class ShareCodeNetwork(engine: HttpClientEngine? = null) : AutoCloseable {
    private val http =
        HttpClient(engine ?: CIO.create()) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
        }

    suspend fun npmFacts(plugin: SharedPlugin): SharedNpmFacts? {
        if (plugin.source != SharedPluginSource.npm) return null
        val version = plugin.version ?: return null
        for (origin in listOf("https://registry.npmjs.org", "https://registry.npmmirror.com")) {
            try {
                val raw =
                    json(
                        "$origin/${plugin.name.encodeURLPathPart()}/${version.encodeURLPathPart()}",
                    )
                val dist = raw["dist"] as? JsonObject
                return SharedNpmFacts(
                    dist?.npString("integrity"),
                    (raw["_npmUser"] as? JsonObject)?.npString("name"),
                    (dist?.get("unpackedSize") as? JsonPrimitive)?.longOrNull,
                    raw.npString("description"),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun fetchGist(link: ShareCodeLink): String {
        val id =
            link.gist?.takeIf { it.matches(Regex("[0-9a-f]{20,40}")) }
                ?: throw ShareFormatException("share/invalid-link")
        val files =
            json("https://api.github.com/gists/$id")["files"] as? JsonObject
                ?: throw ShareFormatException("share/gist-missing")
        for (value in files.values) {
            val item = value as? JsonObject ?: continue
            if (item.npString("filename")?.endsWith(".dshcompose") != true) continue
            val content =
                if (item["truncated"] != JsonPrimitive(true)) item.npString("content") else null
            val text =
                content
                    ?: item.npString("raw_url")?.let { address ->
                        val url = Url(address)
                        if (
                            url.protocol == URLProtocol.HTTPS &&
                                url.host == "gist.githubusercontent.com" &&
                                url.user.isNullOrEmpty() &&
                                url.password.isNullOrEmpty()
                        )
                            get(address)
                        else null
                    }
                    ?: continue
            val envelope = ShareCodeCodec.envelope(text)
            if (!envelope.code.endsWith(".${link.sum}"))
                throw ShareFormatException("share/damaged-code")
            return text
        }
        throw ShareFormatException("share/gist-missing")
    }

    suspend fun createGist(fileName: String, content: String, token: ProviderKey): String =
        withContext(Dispatchers.IO) {
            if (
                content.toByteArray(Charsets.UTF_8).size > ShareCodeCodec.maxFileBytes ||
                    !fileName.endsWith(".dshcompose")
            )
                throw ShareFormatException("share/too-large")
            val raw =
                http
                    .preparePost("https://api.github.com/gists") {
                        header(HttpHeaders.Authorization, "Bearer " + token.text())
                        accept(ContentType.parse("application/vnd.github+json"))
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("description", "dsh-compose profile")
                                put("public", false)
                                putJsonObject("files") {
                                    putJsonObject(fileName) { put("content", content) }
                                }
                            }
                                .toString(),
                        )
                    }
                    .execute { response ->
                        if (response.status.value in setOf(401, 403))
                            throw ShareFormatException("share/gist-auth")
                        if (response.status.value != 201)
                            throw ShareFormatException("share/gist-create-failed")
                        parse(response.boundedBytes(1024 * 1024L))
                    }
            raw.npString("id")?.takeIf { it.matches(Regex("[0-9a-f]{20,40}")) }
                ?: throw ShareFormatException("share/gist-create-failed")
        }

    private suspend fun json(url: String): JsonObject =
        withContext(Dispatchers.Default) { parse(get(url).toByteArray(Charsets.UTF_8)) }

    private suspend fun get(url: String): String =
        http
            .prepareGet(url) {
                accept(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, "dsh-compose")
            }
            .execute { response ->
                if (response.status.value != 200)
                    throw ShareFormatException(
                        if (response.status.value == 404) "share/gist-missing" else "share/network",
                    )
                response.boundedBytes(ShareCodeCodec.maxFileBytes.toLong()).toString(Charsets.UTF_8)
            }

    private fun parse(bytes: ByteArray): JsonObject =
        try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw ShareFormatException("share/invalid-body")

    override fun close() = http.close()
}

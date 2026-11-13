package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.workspaces.WorkspaceByteWindow
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Device credential, bound to its service authority and excluded from diagnostics. */
class HostCredential(val value: String, val authority: String) {
    val deviceId: String
        get() = value.substringBefore('.')

    override fun toString() = "HostCredential(authority=$authority)"
}

/** Ktor owns HTTP and WebSocket I/O, cancellation, and connection resources. */
class HostConnection(
    baseUrl: String,
    val hostHeader: String? = null,
    credential: HostCredential? = null,
    private val engine: HttpClientEngine = CIO.create { endpoint.connectAttempts = 1 },
) : HostTransport {
    val baseUrl: Url =
        URLBuilder(baseUrl)
            .apply {
                parameters.clear()
                fragment = ""
            }
            .build()
    val authority: String
        get() =
            hostHeader
                ?: this.baseUrl.let {
                    val host =
                        if (':' in it.host && !it.host.startsWith('[')) "[${it.host}]" else it.host
                    if (it.port == it.protocol.defaultPort) host else "$host:${it.port}"
                }

    @Volatile
    var credential: HostCredential? = credential
        private set

    @Volatile private var closed = false
    private val socketRequest = AttributeKey<Unit>("dsh-mux")

    private val http =
        HttpClient(engine) {
            followRedirects = false
            expectSuccess = false
            HttpResponseValidator {
                validateResponse { response ->
                    if (
                        response.call.request.attributes.contains(socketRequest) &&
                            response.status.value != 101
                    ) {
                        throw HostHttpException(response.status.value)
                    }
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
            install(WebSockets) {
                pingIntervalMillis = 15_000
                maxFrameSize = 32L * 1024 * 1024
                channels {
                    incoming = bounded(8)
                    outgoing = bounded(64)
                }
            }
        }

    init {
        require(this.baseUrl.protocol in setOf(URLProtocol.HTTP, URLProtocol.HTTPS)) {
            "Expected HTTP or HTTPS"
        }
        require(
            this.baseUrl.protocol == URLProtocol.HTTPS ||
                dev.fughetta.dshcompose.domain.hosts.hostHttpHost(this.baseUrl.host),
        ) {
            "Remote compose services require HTTPS"
        }
        require(this.baseUrl.user.isNullOrEmpty() && this.baseUrl.password.isNullOrEmpty()) {
            "URL credentials are not allowed"
        }
        require(
            hostHeader == null ||
                hostHeader.isNotBlank() && hostHeader.none { it.isWhitespace() || it == '/' },
        )
        require(credential == null || credential.authority == authority) {
            "Credential belongs to a different service"
        }
    }

    internal fun resolve(path: String): Url =
        URLBuilder(baseUrl)
            .apply {
                encodedPath = baseUrl.encodedPath.trimEnd('/') + "/" + path.trimStart('/')
                parameters.clear()
                fragment = ""
            }
            .build()

    fun request(path: String): HttpRequestBuilder {
        if (closed) throw ConnectionClosedException()
        return HttpRequestBuilder().apply {
            url(resolve(path))
            header(HttpHeaders.Host, authority)
            credential
                ?.takeIf { it.authority == authority }
                ?.let { header(HttpHeaders.Authorization, "Bearer ${it.value}") }
        }
    }

    suspend fun login(code: String, deviceName: String = "Android"): HostCredential {
        require(code.isNotEmpty())
        val result =
            jsonRequest(
                "/compose/pair",
                buildJsonObject {
                    put("code", code)
                    put("name", deviceName)
                },
            )
        val granted = HostCredential(result.requiredString("credential"), authority)
        if (granted.deviceId != result.requiredString("deviceId"))
            throw HostProtocolException("Pairing device identity mismatch")
        if (closed) throw ConnectionClosedException()
        credential = granted
        return granted
    }

    suspend fun revokeDevice(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{36}")))
        exchange(request("/compose/devices/$id").apply { method = HttpMethod.Delete }) { response ->
            if (response.status.value != 200) throw HostHttpException(response.status.value)
            if (response.jsonObject(8192).string("revoked") != id)
                throw HostProtocolException("Service did not confirm device revocation")
        }
    }

    override suspend fun call(endpoint: String, args: JsonObject): JsonElement =
        rpcCall(endpoint, args, null)

    override suspend fun callWithTimeout(
        endpoint: String,
        args: JsonObject,
        timeoutMillis: Long,
    ): JsonElement = rpcCall(endpoint, args, timeoutMillis)

    private suspend fun rpcCall(
        endpoint: String,
        args: JsonObject,
        timeoutMillis: Long?,
    ): JsonElement {
        checkEndpoint(endpoint)
        val id = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("id", id)
            put("sentAt", System.currentTimeMillis())
            put("method", endpoint)
            put("args", args)
        }
        try {
            val result = jsonRequest("/compose/rpc", envelope, timeoutMillis = timeoutMillis)
            if (result.string("id") != id) throw HostProtocolException("Request id mismatch")
            result["error"]?.let { throw remoteError(it) }
            return result["result"] ?: JsonNull
        } catch (error: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    kotlinx.coroutines.withTimeout(2000) {
                        jsonRequest("/compose/cancel", buildJsonObject { put("id", id) })
                    }
                }
            }
            throw error
        }
    }

    internal suspend fun jsonRequest(
        path: String,
        body: JsonObject? = null,
        maxBytes: Long = 32L * 1024 * 1024,
        timeoutMillis: Long? = null,
    ): JsonObject {
        val req =
            request(path).apply {
                if (timeoutMillis != null)
                    timeout {
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                if (body != null) {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        return exchange(req) { response ->
            if (response.status.value != 200) {
                if (
                    (path == "/compose/pair" || response.status.value !in setOf(401, 403)) &&
                        response.contentType()?.withoutParameters() == ContentType.Application.Json
                ) {
                    val failure = response.jsonObject(65536)["error"]
                    if (failure != null) throw remoteError(failure)
                }
                throw HostHttpException(response.status.value)
            }
            response.jsonObject(maxBytes)
        }
    }

    // execute keeps the response streaming and closes it even on parse failure or cancellation.
    // No request retry plugin is installed: mutations with lost responses have unknown outcomes.
    suspend fun <T> exchange(
        request: HttpRequestBuilder,
        read: suspend (HttpResponse) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            if (closed) throw ConnectionClosedException()
            try {
                http.prepareRequest(request).execute { read(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HostException) {
                throw error
            } catch (_: Exception) {
                throw HostTransportException()
            }
        }

    override val supportsTerminals: Boolean = true

    override val supportsSessionExport: Boolean = true

    override suspend fun copySessionArchive(
        sessionId: String,
        destination: OutputStream,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long = copyHttpSessionArchive(sessionId, destination, onProgress)

    suspend fun openSocket(path: String): DefaultClientWebSocketSession {
        val request =
            request(path).apply {
                url.protocol =
                    if (baseUrl.protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
                attributes.put(socketRequest, Unit)
            }
        return try {
            http.webSocketSession { takeFrom(request) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HostException) {
            throw error
        } catch (_: Exception) {
            throw HostTransportException()
        }
    }

    override suspend fun openMuxLink(): MultiplexerLink {
        val request =
            request("/compose/events").apply {
                url.protocol =
                    if (baseUrl.protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
                attributes.put(socketRequest, Unit)
            }
        return try {
            ktorMuxLink(http.webSocketSession { takeFrom(request) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: HostException) {
            throw error
        } catch (_: Exception) {
            throw HostTransportException()
        }
    }

    override suspend fun readWorkspaceBytes(
        sessionId: String,
        path: String,
        offset: Long,
        length: Int,
    ): WorkspaceByteWindow = readHttpWorkspaceBytes(sessionId, path, offset, length)

    override suspend fun renderOfficePdf(
        sessionId: String,
        path: String,
        destination: OutputStream,
        maximumBytes: Long,
    ): OfficePdfResult = renderHttpOfficePdf(sessionId, path, destination, maximumBytes)

    override suspend fun uploadFile(
        sessionId: String,
        file: File,
        name: String,
        onProgress: suspend (Long, Long?) -> Unit,
    ): FileReceipt = uploadHttpFile(sessionId, file, name, onProgress)

    /** Never replay a deletion or infer success from local state. */
    override suspend fun deleteSessionPermanently(sessionId: String) {
        require(
            sessionId.isNotEmpty() &&
                sessionId.length <= 256 &&
                sessionId.none { it.code < 32 || it.code == 127 },
        )
        val body = buildJsonObject { put("sessionId", sessionId) }
        val req =
            request("/compose/extensions/v1/sessions/delete").apply {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        exchange(req) { response ->
            if (response.status.value != 200) {
                val value =
                    try {
                        response.jsonObject(8192)
                    } catch (_: HostProtocolException) {
                        null
                    }
                val code = (value?.get("error") as? JsonObject)?.string("code")
                if (!code.isNullOrEmpty() && code.length <= 80) throw HostRemoteException(code)
                if (response.status.value in setOf(404, 405, 501))
                    throw HostRemoteException("unavailable")
                throw HostHttpException(response.status.value)
            }
            val value = response.jsonObject(8192)
            if (value.boolean("deleted") != true || value.string("sessionId") != sessionId) {
                throw HostProtocolException(
                    "Host did not confirm deletion of the requested session",
                )
            }
        }
    }

    override fun close() {
        closed = true
        credential = null
        http.coroutineContext.cancel()
        http.close()
        engine.close()
    }
}

suspend fun HttpResponse.jsonObject(maxBytes: Long): JsonObject {
    if (contentType()?.withoutParameters() != ContentType.Application.Json)
        throw HostProtocolException("Expected application/json")
    return parseObject(boundedBody(maxBytes).toString(Charsets.UTF_8))
}

suspend fun HttpResponse.boundedBytes(maxBytes: Long): ByteArray =
    boundedBody(maxBytes).toByteArray()

private suspend fun HttpResponse.boundedBody(maxBytes: Long): ByteArrayOutputStream {
    require(maxBytes in 1..Int.MAX_VALUE.toLong())
    if ((contentLength() ?: 0) > maxBytes)
        throw HostProtocolException("Response exceeds byte limit")
    val channel = bodyAsChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count =
            channel.readAvailable(
                buffer,
                0,
                minOf(buffer.size.toLong(), maxBytes + 1 - bytes.size()).toInt(),
            )
        if (count < 0) break
        if (count == 0) continue
        if (bytes.size().toLong() + count > maxBytes)
            throw HostProtocolException("Response exceeds byte limit")
        bytes.write(buffer, 0, count)
    }
    return bytes
}

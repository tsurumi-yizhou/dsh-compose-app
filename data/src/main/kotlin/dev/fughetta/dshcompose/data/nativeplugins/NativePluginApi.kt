package dev.fughetta.dshcompose.data.nativeplugins

import dev.fughetta.dshcompose.data.domainOperation
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*

fun NativePluginScope.json() = buildJsonObject {
    listOf(
            "sessionId" to sessionId,
            "turnId" to turnId,
            "messageId" to messageId,
            "callId" to callId,
        )
        .forEach { (key, value) -> value?.takeIf(String::isNotEmpty)?.let { put(key, it) } }
}

internal fun NativePluginCaps.query(builder: URLBuilder) {
    builder.parameters.append("v", "1")
    builder.parameters.append("app", app)
    builder.parameters.append("locale", locale)
    builder.parameters.append(
        "surfaces",
        "composer.actions,message.actions,composer.strip,panels,composer.tools,session.actions,turn.note,plugin",
    )
    builder.parameters.append("components", components.joinToString(","))
    if (features.isNotEmpty()) builder.parameters.append("features", features.joinToString(","))
}

fun NativePluginInvocation.json() = buildJsonObject {
    put("v", 1)
    put("pkg", pkg)
    put("id", id)
    put("surface", surface)
    put("scope", scope.json())
    put("locale", locale)
    action?.let { put("action", it) }
    value?.let { put("value", it.toJsonElement()) }
    stream?.let { put("stream", it) }
    if (context.fields.isNotEmpty()) put("context", context.toJsonElement())
    put("userGesture", userGesture)
}

class NativePluginApi(private val client: ProtocolClient) : AutoCloseable {
    val generation = client.mux.state.value.generation
    @Volatile private var closed = false
    val active: Boolean
        get() = !closed && client.mux.state.value.generation == generation

    private fun ensureCurrent() {
        if (!active) throw CancellationException("Native plugin connection retired")
    }

    override fun close() {
        closed = true
    }

    private suspend fun <T> exchange(
        request: HttpRequestBuilder,
        read: suspend (HttpResponse) -> T,
    ): T {
        ensureCurrent()
        return domainOperation {
            connection.exchange(request) { response -> read(response).also { ensureCurrent() } }
        }
    }

    private val connection =
        client.connection as? HostConnection ?: throw OperationFailure("dsh-compose/unreachable")

    suspend fun contributions(
        caps: NativePluginCaps,
        previous: NativePluginContributions? = null,
    ): NativePluginContributions? {
        val request =
            connection.request("/compose/extensions/v1/contributions").apply {
                caps.query(url)
                previous?.rev?.let { header(HttpHeaders.IfNoneMatch, "\"$it\"") }
            }
        return exchange(request) { response ->
            if (response.status.value == 304 && previous != null) return@exchange previous
            if (response.status.value == 404) return@exchange null
            val raw = response.jsonObject(256 * 1024)
            checkResponse(response.status.value, raw)
            val plugins =
                (raw["plugins"] as? JsonArray
                        ?: throw HostProtocolException("Invalid native contributions"))
                    .mapNotNull {
                        parsePlugin(it as? JsonObject ?: return@mapNotNull null, caps.features)
                    }
            NativePluginContributions(
                raw.npString("rev"),
                plugins,
                raw.toToolPayload() as ToolPayload.Obj,
            )
        }
    }

    suspend fun view(
        pkg: String,
        id: String,
        scope: NativePluginScope,
        caps: NativePluginCaps,
    ): NativePluginView {
        val raw =
            request(
                "/compose/extensions/v1/views/${segments(pkg)}/${id.encodeURLPathPart()}",
                query = {
                    caps.query(it)
                    scope.json().forEach { (key, value) ->
                        it.parameters.append(key, (value as JsonPrimitive).content)
                    }
                },
            )
        return NativePluginView(
            raw["view"]?.toToolPayload() ?: ToolPayload.Null,
            raw["data"]?.toToolPayload() ?: ToolPayload.Null,
            raw.npString("rev"),
            raw.npBool("live"),
            raw.toToolPayload() as ToolPayload.Obj,
        )
    }

    suspend fun invoke(invocation: NativePluginInvocation): JsonObject =
        request("/compose/extensions/v1/invoke", invocation.json())

    suspend fun asset(pkg: String, path: String): ByteArray {
        require(
            path.length <= 256 &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                !path.contains('\\'),
        )
        return exchange(
            connection.request("/compose/extensions/v1/assets/${segments(pkg)}/${segments(path)}"),
        ) { response ->
            if (response.status.value != 200) throw HostHttpException(response.status.value)
            response.boundedBytes(512 * 1024)
        }
    }

    suspend fun media(
        pkg: String,
        id: String,
        lease: String,
        key: String?,
    ): Pair<ByteArray, String> {
        require(nativePluginId(id) && lease.length in 1..256)
        val req =
            connection
                .request("/compose/extensions/v1/media/${segments(pkg)}/${id.encodeURLPathPart()}")
                .apply {
                    url.parameters.append("lease", lease)
                    key?.let { url.parameters.append("key", it) }
                    timeout { requestTimeoutMillis = 35_000 }
                }
        return exchange(req) { response ->
            if (response.status.value != 200) {
                checkResponse(response.status.value, response.jsonObject(4096))
            }
            val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';').orEmpty()
            if (type !in setOf("audio/mpeg", "audio/mp4", "audio/wav", "audio/ogg", "audio/aac"))
                throw HostProtocolException("Invalid plugin audio")
            response.boundedBytes(16L * 1024 * 1024) to type
        }
    }

    suspend fun uploadPickedFile(
        request: String,
        stream: String,
        name: String,
        mediaType: String,
        bytes: ByteArray,
    ) {
        require(request.matches(Regex("[A-Za-z0-9_-]{32}")) && bytes.size <= 8 * 1024 * 1024)
        val req =
            connection.request("/compose/extensions/v1/files/$request/upload").apply {
                method = HttpMethod.Post
                url.parameters.append("stream", stream)
                url.parameters.append("name", name)
                url.parameters.append("mediaType", mediaType)
                setBody(bytes)
                timeout { requestTimeoutMillis = 30_000 }
            }
        exchange(req) { response ->
            val body = response.jsonObject(4096)
            checkResponse(response.status.value, body)
            if (body["file"] !is JsonObject)
                throw HostProtocolException("Invalid selected file receipt")
        }
    }

    suspend fun completeSelection(request: String, stream: String, status: String): JsonObject {
        require(
            request.matches(Regex("[A-Za-z0-9_-]{32}")) &&
                status in setOf("selected", "cancelled", "error"),
        )
        return request(
            "/compose/extensions/v1/files/$request/complete",
            buildJsonObject { put("status", status) },
        ) {
            it.parameters.append("stream", stream)
        }
    }

    internal suspend fun socket(): io.ktor.client.plugins.websocket.DefaultClientWebSocketSession {
        ensureCurrent()
        val socket = connection.openSocket("/compose/extensions/v1/stream")
        if (!active) {
            socket.cancel()
            ensureCurrent()
        }
        return socket
    }

    private suspend fun request(
        path: String,
        body: JsonObject? = null,
        query: (URLBuilder) -> Unit = {},
    ): JsonObject {
        val req =
            connection.request(path).apply {
                query(url)
                timeout { requestTimeoutMillis = 8_000 }
                if (body != null) {
                    method = HttpMethod.Post
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        return exchange(req) { response ->
            response.jsonObject(96 * 1024).also { checkResponse(response.status.value, it) }
        }
    }

    private fun checkResponse(status: Int, body: JsonObject) {
        if (status != 200) {
            val code = (body["error"] as? JsonObject)?.npString("code")
            throw HostRemoteException(
                if (code == null) "dsh-compose/http-$status"
                else if (code.startsWith("dsh-compose/")) code else "dsh-compose/$code",
            )
        }
    }

    private fun segments(value: String) =
        value.split('/').joinToString("/") { it.encodeURLPathPart() }
}

private fun parsePlugin(raw: JsonObject, features: Set<String>): NativePlugin? {
    val pkg = raw.npString("pkg")?.takeIf(String::isNotEmpty) ?: return null
    if (raw.npString("state") !in setOf(null, "ok") || raw["native"] == JsonPrimitive(false))
        return null
    val contributes = raw["contributes"] as? JsonObject ?: JsonObject(emptyMap())
    val actions =
        listOf("composer.actions", "message.actions", "composer.tools", "session.actions")
            .flatMap { surface ->
                contributes
                    .npObjects(surface)
                    .filter {
                        it["ready"] != JsonPrimitive(false) &&
                            it.npStrings("requires").all(features::contains)
                    }
                    .take(2)
                    .mapNotNull { action ->
                        val id = action.npString("id") ?: return@mapNotNull null
                        val label = action.npString("label") ?: return@mapNotNull null
                        NativePluginAction(
                            pkg,
                            id,
                            label.take(if (surface == "composer.tools") 8 else 12),
                            surface,
                            nativePluginEffects(action["effect"]).map {
                                it.toToolPayload() as ToolPayload.Obj
                            },
                        )
                    }
            }
    val panels =
        contributes
            .npObjects("panels")
            .filter { it["ready"] != JsonPrimitive(false) }
            .take(4)
            .mapNotNull { panel ->
                val id = panel.npString("id") ?: return@mapNotNull null
                val placements =
                    (panel["placement"] as? JsonPrimitive)?.content?.let(::listOf)
                        ?: panel.npStrings("placement").ifEmpty { listOf("session.tab") }
                NativePluginPanel(
                    pkg,
                    id,
                    (panel.npString("title") ?: id).take(16),
                    placements,
                    panel.npBool("live"),
                    panel.npString("presentation") == "sheet",
                    panel.npString("micro"),
                )
            }
    return NativePlugin(
        pkg,
        raw.npString("title") ?: pkg,
        raw.npStrings("wants").toSet(),
        raw.npStrings("capabilities").toSet(),
        actions,
        panels,
        contributes
            .npObjects("composer.strip")
            .filter { it["ready"] != JsonPrimitive(false) }
            .map { it.toToolPayload() as ToolPayload.Obj },
        contributes
            .npObjects("turn.note")
            .filter { it.npBool("ready") }
            .mapNotNull { it.npString("id") },
        contributes.npObjects("media").mapNotNull { it.npString("id") }.toSet(),
        contributes.npObjects("overlays").map { it.toToolPayload() as ToolPayload.Obj },
        raw.toToolPayload() as ToolPayload.Obj,
        dependencies = raw.npStrings("dependencies").toSet(),
    )
}

fun nativePluginEffects(value: JsonElement?): List<JsonObject> =
    when (value) {
        is JsonObject -> listOf(value)
        is JsonArray -> value.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

package dev.fughetta.dshcompose.nativeplugins

import android.content.ClipData
import android.content.ClipboardManager
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.plugins.PluginUiRepository
import dev.fughetta.dshcompose.nativefiles.*
import dev.fughetta.dshcompose.nativemedia.*
import dev.fughetta.dshcompose.nativeplugins.NativePluginsViewModel.Owner
import dev.fughetta.dshcompose.ui.nativeplugins.*
import dev.fughetta.dshcompose.ui.toUiJson
import dev.fughetta.dshcompose.ui.toUiPayload
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal suspend fun NativePluginsViewModel.applyEffects(
    plugin: NativePlugin,
    effects: List<JsonObject>,
    panelId: String,
    context: NativePluginScope,
    connection: String?,
    tapped: Long,
    gesture: Boolean,
    dynamic: Boolean,
    originApi: PluginUiRepository? = api,
    originMux: ConnectionGeneration? = originApi?.generation,
) {
    val sourceApi = originApi ?: return
    val epoch = generation
    val sourceHost = hostKey
    suspend fun emit(effect: NativePluginEffect) {
        if (
            generation == epoch &&
                context.sessionId == sessionId &&
                api === sourceApi &&
                sourceApi.nativeActive &&
                sourceApi.generation == originMux
        )
            outgoing.emit(NativePluginDispatch(sourceHost, context, effect, epoch, originMux))
    }
    for (raw in effects.take(16)) {
        if (
            generation != epoch ||
                context.sessionId != sessionId ||
                api !== sourceApi ||
                !sourceApi.nativeActive ||
                sourceApi.generation != originMux
        )
            return
        val type = raw.viewString("type") ?: raw.keys.singleOrNull() ?: continue
        val value = raw[type]
        val body =
            if (raw.viewString("type") != null) raw
            else
                (value as? JsonObject
                    ?: buildJsonObject {
                        put(
                            when (type) {
                                "command" -> "name"
                                "open" -> "page"
                                "link" -> "url"
                                "patch" -> "data"
                                "refresh" -> "id"
                                else -> "text"
                            },
                            value ?: JsonNull,
                        )
                    })
        if (
            type !in setOf("patch", "refresh") &&
                (!gesture || System.nanoTime() - tapped !in 0..3_000_000_000L || !foreground)
        ) {
            issue(plugin.pkg, "gesture-expired")
            continue
        }
        when (type) {
            "insert" ->
                body.viewString("text")?.take(8000)?.let { emit(NativePluginEffect.Insert(it)) }
            "command" -> {
                val name = body.viewString("name")?.trim()?.removePrefix("/") ?: continue
                if (nativePluginId(name) && name != "permission")
                    emit(
                        NativePluginEffect.Command(
                            "/$name" + (body.viewString("args")?.take(4000)?.let { " $it" } ?: ""),
                        ),
                    )
                else issue(plugin.pkg, "bad-effect")
            }
            "open" -> {
                val target = body.viewString("panel") ?: body.viewString("page") ?: continue
                if (body.containsKey("panel") || target.startsWith("panel:"))
                    openPanel(plugin.pkg, target.removePrefix("panel:"), context)
                else if (target in NativePluginsViewModel.pages)
                    emit(NativePluginEffect.Open(target))
                else issue(plugin.pkg, "unknown-page")
            }
            "link" ->
                body.viewString("url")?.let { target ->
                    val uri = runCatching { java.net.URI(target) }.getOrNull()
                    if (
                        target.length <= 2048 &&
                            uri?.scheme == "https" &&
                            !uri.host.isNullOrBlank() &&
                            uri.userInfo == null &&
                            consent(plugin, url = target)
                    )
                        emit(NativePluginEffect.Link(target))
                }
            "toast" ->
                body.viewString("text")?.let { text ->
                    val now = System.nanoTime()
                    if (now - (toasts[plugin.pkg] ?: 0) >= 2_000_000_000) {
                        toasts[plugin.pkg] = now
                        emit(NativePluginEffect.Toast(text.replace(Regex("\\s+"), " ").take(40)))
                    }
                }
            "copy" ->
                body.viewString("text")?.take(8000)?.let { text ->
                    contextClipboard().setPrimaryClip(ClipData.newPlainText("", text))
                }
            "refresh" ->
                if ((body.viewString("id") ?: panelId) == state.value.panel?.panel?.id) loadPanel()
            "patch" ->
                if (
                    (body.viewString("id") ?: panelId) == state.value.panel?.panel?.id &&
                        body["data"] is JsonObject
                )
                    mutable.update {
                        it.copy(
                            panel =
                                it.panel?.let { panel ->
                                    panel.copy(
                                        data =
                                            nativePluginMerge(
                                                    panel.data.toUiPayload(),
                                                    body.getValue("data").toUiPayload(),
                                                )
                                                .toUiJson(),
                                    )
                                },
                        )
                    }
            "pickFile",
            "pickImage" ->
                if (dynamic && connection != null)
                    pickFile(plugin, body, connection, context, type == "pickImage")
                else issue(plugin.pkg, "bad-effect")
            "audio",
            "overlay" ->
                if (dynamic && connection != null) capability(plugin, body, context, connection)
                else issue(plugin.pkg, "bad-effect")
            else -> issue(plugin.pkg, "unknown-effect")
        }
    }
}

private fun NativePluginsViewModel.contextClipboard() =
    context.getSystemService(ClipboardManager::class.java)

private suspend fun NativePluginsViewModel.pickFile(
    plugin: NativePlugin,
    effect: JsonObject,
    connection: String,
    context: NativePluginScope,
    images: Boolean,
) {
    val request = effect.viewString("request") ?: return
    val action = effect.viewString("action") ?: return
    val count = (effect["maxFiles"] as? JsonPrimitive)?.intOrNull ?: return
    val bytes = (effect["maxBytes"] as? JsonPrimitive)?.intOrNull ?: return
    val total = (effect["maxTotalBytes"] as? JsonPrimitive)?.intOrNull ?: return
    if (
        !request.matches(Regex("[A-Za-z0-9_-]{32}")) ||
            !nativePluginId(action) ||
            count !in 1..4 ||
            bytes !in 1..8 * 1024 * 1024 ||
            total !in 1..16 * 1024 * 1024
    ) {
        issue(plugin.pkg, "bad-effect")
        return
    }
    val bridge = api ?: return
    val socket = stream ?: return
    val epoch = generation
    val launch = launchFiles ?: return
    val originApi = api ?: return
    val originMux = originApi.generation
    if ("files" !in plugin.capabilities || "files" !in socket.negotiatedFeatures) {
        issue(plugin.pkg, "capability-denied")
        return
    }
    val keep = socket.selectionHold()
    try {
        var status = "cancelled"
        try {
            val selected =
                files.pick(NativeFileRequest(request, count, bytes, total, images), launch)
            if (
                selected != null &&
                    generation == epoch &&
                    socket.connectionId == connection &&
                    sessionId == context.sessionId
            ) {
                selected.forEach { file ->
                    val data =
                        files.read(request, file.handle)
                            ?: throw OperationFailure("dsh-compose/file-unreadable")
                    bridge.uploadPickedFile(
                        request,
                        connection,
                        file.name,
                        file.mediaType,
                        data,
                    )
                }
                status = "selected"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            status = "error"
        }
        if (
            generation == epoch &&
                socket.connectionId == connection &&
                sessionId == context.sessionId
        ) {
            val result = bridge.completeSelection(request, connection, status)
            // Chooser callbacks may only update data or show a toast; never chain another
            // chooser or command.
            applyEffects(
                plugin,
                result.effects
                    .map { it.toUiJson() as JsonObject }
                    .filter { it.viewString("type") in setOf("patch", "refresh", "toast") },
                "",
                context,
                connection,
                System.nanoTime(),
                true,
                false,
                originApi,
                originMux,
            )
        }
    } finally {
        files.cancel(request)
        keep.close()
    }
}

private suspend fun NativePluginsViewModel.capability(
    plugin: NativePlugin,
    effect: JsonObject,
    context: NativePluginScope,
    connection: String,
) {
    val type = effect.viewString("type") ?: return
    val op = effect.viewString("op") ?: return
    val instance = effect.viewString("instance") ?: return
    val lease = effect.viewString("lease") ?: return
    val socket = stream ?: return
    if (
        mediaFailed ||
            type !in plugin.capabilities ||
            type !in socket.negotiatedFeatures ||
            !nativePluginId(instance) ||
            lease.length !in 1..256 ||
            socket.connectionId != connection ||
            sessionId != context.sessionId
    ) {
        issue(plugin.pkg, "capability-denied")
        return
    }
    val start = op in setOf("play", "start", "show")
    val allowed =
        when (type) {
            "audio" -> setOf("play", "pause", "resume", "stop")
            "overlay" -> setOf("show", "hide")
            else -> emptySet()
        }
    if (op !in allowed) {
        issue(plugin.pkg, "bad-effect")
        return
    }
    val keys =
        setOf("type", "op", "instance", "lease") +
            when {
                type == "audio" && op == "play" -> setOf("src", "key")
                type == "overlay" -> setOf("id")
                else -> emptySet()
            }
    if (effect.keys.any { it !in keys }) {
        issue(plugin.pkg, "bad-effect")
        return
    }
    if (type == "audio" && op == "play") {
        val resource =
            effect.viewString("src")?.takeIf { it.startsWith("media:") }?.removePrefix("media:")
        val clipKey = effect.viewString("key")
        if (
            resource !in plugin.media ||
                clipKey != null && !clipKey.matches(Regex("[A-Za-z0-9_-]{1,128}"))
        ) {
            issue(plugin.pkg, "undeclared-media")
            return
        }
    }
    if (type == "overlay" && plugin.overlays.none { it.text("id") == effect.viewString("id") }) {
        issue(plugin.pkg, "undeclared-overlay")
        return
    }
    var owned = owner
    if (
        owned == null ||
            owned.pkg != plugin.pkg ||
            owned.instance != instance ||
            owned.key.lease != lease ||
            owned.context != context
    ) {
        if (!start) return
        revoke()
        owned =
            Owner(
                plugin.pkg,
                instance,
                MediaLease(
                    UUID.randomUUID().toString().replace("-", ""),
                    generation.toLong(),
                    lease,
                ),
                sessionId,
                connection,
                context,
            )
        if (socket.hold() != connection) {
            socket.release()
            return
        }
        try {
            media.activate(owned.key, plugin.title)
            owner = owned
        } catch (error: Exception) {
            socket.release()
            throw error
        }
    }
    val current = owned
    try {
        if (start && (type in current.ended || !current.roles.add(type))) return
        when (type) {
            "audio" ->
                when (op) {
                    "play" -> {
                        val resource =
                            effect
                                .viewString("src")
                                ?.takeIf { it.startsWith("media:") }
                                ?.removePrefix("media:") ?: return
                        if (resource !in plugin.media) {
                            issue(plugin.pkg, "undeclared-media")
                            return
                        }
                        val result =
                            api?.media(plugin.pkg, resource, lease, effect.viewString("key"))
                                ?: return
                        if (owner !== current || socket.connectionId != connection || !foreground)
                            return
                        val file =
                            withContext(Dispatchers.IO) {
                                val directory =
                                    File(
                                        this@capability.context.cacheDir,
                                        "native-media/${current.key.owner}",
                                    )
                                directory.mkdirs()
                                File(directory, "clip").also { it.writeBytes(result.first) }
                            }
                        if (owner !== current || socket.connectionId != connection || !foreground) {
                            file.delete()
                            return
                        }
                        audioFile = file
                        media.play(current.key, file.path, result.second)
                    }
                    "pause" -> media.pause(current.key)
                    "resume" -> media.resume(current.key)
                    "stop" -> media.stop(current.key)
                }
            "overlay" -> {
                val declaration =
                    plugin.overlays.find { it.text("id") == effect.viewString("id") } ?: return
                if (op == "hide") media.hideOverlay(current.key)
                else {
                    media.showOverlay(
                        current.key,
                        declaration.text("text").orEmpty().take(240),
                    )
                    declaration.text("channel")?.let { channel ->
                        val feed = socket.subscribe(plugin.pkg, channel, context, micro = true)
                        mediaFeed = feed
                        mediaJob = scope.launch {
                            feed.state.sample(100).collect { data ->
                                if (owner === current)
                                    data.data.text("text")?.let {
                                        media.showOverlay(current.key, it, true)
                                    }
                            }
                        }
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        issue(plugin.pkg, code(error))
        if (owner === current) {
            current.roles.remove(type)
            current.ended.add(type)
            socket.capabilityEvent(
                lease,
                ++current.seq,
                if (type == "overlay") "overlay.closed" else "$type.error",
                code(error),
            )
            if (current.roles.isEmpty()) revoke()
        }
    }
}

internal fun NativePluginsViewModel.mediaEvent(event: NativeMediaEvent) {
    val current = owner?.takeIf { it.key == event.owner } ?: return
    if (event.kind == "owner.revoked") {
        revoke()
        return
    }
    if (
        event.kind in
            setOf(
                "audio.ended",
                "audio.stopped",
                "audio.error",
                "overlay.closed",
            )
    ) {
        val role = event.kind.substringBefore('.')
        if (!current.roles.remove(role) || !current.ended.add(role)) return
        if (role == "overlay") {
            mediaFeed?.close()
            mediaFeed = null
            mediaJob?.cancel()
            mediaJob = null
        }
    }
    stream?.capabilityEvent(current.key.lease, ++current.seq, event.kind, event.reason)
    if (current.roles.isEmpty()) revoke()
}

internal fun NativePluginsViewModel.revoke() {
    val previous = owner
    owner = null
    mediaFeed?.close()
    mediaFeed = null
    mediaJob?.cancel()
    mediaJob = null
    if (previous != null) {
        previous.roles.forEach { role ->
            stream?.capabilityEvent(
                previous.key.lease,
                ++previous.seq,
                when (role) {
                    "audio" -> "audio.stopped"
                    else -> "overlay.closed"
                },
                "context",
            )
        }
        try {
            media.disposeOwner(previous.key)
        } catch (_: NativeMediaException) {
            mediaFailed = true
            issue(previous.pkg, "native-close-failed")
        }
        stream?.release()
    }
    audioFile?.let { file ->
        scope.launch(Dispatchers.IO + NonCancellable) {
            file.delete()
            file.parentFile?.delete()
        }
    }
    audioFile = null
}

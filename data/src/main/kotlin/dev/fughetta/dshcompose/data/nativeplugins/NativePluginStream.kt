package dev.fughetta.dshcompose.data.nativeplugins

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.*
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * One authenticated socket per host. Only visible surfaces subscribe; old conversations allocate no
 * feeds.
 */
class NativePluginStream(
    private val api: NativePluginApi,
    private val caps: NativePluginCaps,
    parent: CoroutineScope,
) : Closeable {
    private val scope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private data class Subscription(
        val pkg: String,
        val id: String,
        val scope: NativePluginScope,
        val state: MutableStateFlow<NativePluginData>,
        val micro: Boolean,
    )

    private val subscriptions = linkedMapOf<String, Subscription>()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val early = linkedMapOf<String, Pair<Long, JsonObject>>()
    private var socket: DefaultClientWebSocketSession? = null
    private var runner: Job? = null
    private var backgroundJob: Job? = null
    private var background = false
    private var backgroundAt = 0L
    private var selectionUntil = 0L
    private var sleeping = false
    private var closed = false
    private var epoch = 0
    private var holds = 0
    private var welcomed = CompletableDeferred<String>()
    private val mutableLink = MutableStateFlow(NativePluginLink.Idle)
    val link: StateFlow<NativePluginLink> = mutableLink.asStateFlow()
    private val mutableChanges =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val changes = mutableChanges.asSharedFlow()
    var connectionId: String? = null
        private set

    var negotiatedFeatures: Set<String> = emptySet()
        private set

    fun subscribe(
        pkg: String,
        id: String,
        context: NativePluginScope,
        data: ToolPayload = ToolPayload.Null,
        rev: String? = null,
        micro: Boolean = false,
    ): NativePluginFeed {
        check(!closed)
        require(subscriptions.size < 32)
        val key = UUID.randomUUID().toString().replace("-", "")
        val sub =
            Subscription(pkg, id, context, MutableStateFlow(NativePluginData(data, rev)), micro)
        subscriptions[key] = sub
        if (connectionId != null) scope.launch { sendSubscription(key, sub) } else ensureConnected()
        var disposed = false
        return NativePluginFeed(sub.state.asStateFlow()) {
            if (!disposed) {
                disposed = true
                subscriptions.remove(key)
                scope.launch {
                    send(
                        buildJsonObject {
                            put("t", if (micro) "micro.unsub" else "unsub")
                            put("sub", key)
                        },
                    )
                    stopIfUnused()
                }
            }
        }
    }

    suspend fun hold(): String {
        check(!closed)
        holds++
        ensureConnected()
        return try {
            withTimeout(2_000) { welcomed.await() }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    fun release() {
        if (holds > 0) holds--
        stopIfUnused()
    }

    private fun stopIfUnused() {
        if (holds == 0 && subscriptions.isEmpty()) {
            disconnect()
            mutableLink.value = NativePluginLink.Idle
        }
    }

    suspend fun outcome(id: String, timeoutMs: Long): JsonObject {
        early
            .remove(id)
            ?.takeIf { System.nanoTime() - it.first < 5_000_000_000L }
            ?.let {
                return it.second
            }
        val waiter = pending.getOrPut(id) { CompletableDeferred() }
        return try {
            withTimeout(timeoutMs.coerceIn(1_000, 60_000)) { waiter.await() }
        } catch (error: TimeoutCancellationException) {
            cancel(id)
            throw OperationFailure("dsh-compose/timeout")
        } catch (error: CancellationException) {
            cancel(id)
            throw error
        } finally {
            pending.remove(id)
        }
    }

    fun cancel(id: String) {
        scope.launch {
            send(
                buildJsonObject {
                    put("t", "cancel")
                    put("pending", id)
                },
            )
        }
    }

    fun capabilityEvent(lease: String, seq: Int, event: String, reason: String? = null) {
        if (connectionId == null || event.substringBefore('.') !in negotiatedFeatures) return
        scope.launch {
            send(
                buildJsonObject {
                    put("t", "cap.event")
                    put("lease", lease)
                    put("seq", seq)
                    put("event", event)
                    reason?.let { put("reason", it.take(120)) }
                },
            )
        }
    }

    fun foreground(value: Boolean) {
        if (value) {
            background = false
            backgroundAt = 0L
            backgroundJob?.cancel()
            sleeping = false
            ensureConnected()
        } else {
            if (!background) backgroundAt = System.nanoTime()
            background = true
            scheduleBackground()
        }
    }

    /** File chooser keeps its bounded callback socket alive while the system Activity covers us. */
    fun selectionHold(): Closeable {
        check(selectionUntil == 0L)
        val deadline = System.nanoTime() + 300_000_000_000L
        selectionUntil = deadline
        if (background) scheduleBackground()
        return Closeable {
            if (selectionUntil == deadline) {
                selectionUntil = 0L
                if (background) scheduleBackground()
            }
        }
    }

    private fun scheduleBackground() {
        backgroundJob?.cancel()
        val deadline = maxOf(backgroundAt + 60_000_000_000L, selectionUntil)
        backgroundJob = scope.launch {
            delay(((deadline - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L))
            sleeping = true
            disconnect()
            mutableLink.value = NativePluginLink.Background
        }
    }

    private fun ensureConnected() {
        if (closed || sleeping || runner?.isActive == true || holds == 0 && subscriptions.isEmpty())
            return
        val started = ++epoch
        runner = scope.launch {
            var attempt = 0
            while (isActive && !sleeping && (holds > 0 || subscriptions.isNotEmpty())) {
                try {
                    mutableLink.value = NativePluginLink.Connecting
                    val current = api.socket()
                    if (started != epoch) {
                        current.cancel()
                        return@launch
                    }
                    socket = current
                    send(
                        buildJsonObject {
                            put("t", "hello")
                            put("v", JsonArray(listOf(JsonPrimitive(1))))
                            put("locale", caps.locale)
                            put("features", JsonArray(caps.features.map(::JsonPrimitive)))
                        },
                    )
                    while (isActive) {
                        val frame = withTimeout(60_000) { current.incoming.receive() }
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        if (text.toByteArray().size > 96 * 1024)
                            throw HostProtocolException("Plugin stream frame too large")
                        val value =
                            try {
                                Json.parseToJsonElement(text) as? JsonObject
                            } catch (_: Exception) {
                                null
                            } ?: continue
                        accept(value)
                        if (connectionId != null) attempt = 0
                    }
                } catch (error: CancellationException) {
                    if (!isActive) throw error
                } catch (_: Exception) {
                    /* Reconnect read subscriptions; never replay an invoke. */
                } finally {
                    if (started == epoch) clearConnection()
                }
                mutableLink.value = NativePluginLink.Backoff
                delay(if (attempt >= 5) 30_000 else 1_000L shl attempt++)
            }
        }
    }

    private fun clearConnection() {
        socket?.cancel()
        socket = null
        connectionId = null
        negotiatedFeatures = emptySet()
        welcomed.cancel()
        welcomed = CompletableDeferred()
        pending.values.forEach {
            it.completeExceptionally(OperationFailure("dsh-compose/disconnected"))
        }
        pending.clear()
        early.clear()
        subscriptions.values
            .filter { it.micro }
            .forEach { it.state.value = NativePluginData(ToolPayload.Null, null) }
    }

    private fun disconnect() {
        epoch++
        runner?.cancel()
        runner = null
        clearConnection()
    }

    private suspend fun send(value: JsonObject) {
        socket?.send(Frame.Text(value.toString()))
    }

    private suspend fun sendSubscription(key: String, sub: Subscription) {
        if (sub.micro && "micro" !in negotiatedFeatures) {
            sub.state.value = sub.state.value.copy(ended = "unsupported")
            return
        }
        send(
            buildJsonObject {
                put("t", if (sub.micro) "micro.sub" else "sub")
                put("sub", key)
                put("pkg", sub.pkg)
                put("id", sub.id)
                put("scope", sub.scope.json())
                if (!sub.micro) sub.state.value.rev?.let { put("rev", it) }
            },
        )
    }

    private suspend fun accept(raw: JsonObject) {
        when (raw.npString("t")) {
            "welcome" -> {
                connectionId =
                    raw.npString("conn") ?: throw HostProtocolException("Missing plugin connection")
                negotiatedFeatures =
                    raw.npStrings("features").filter(caps.features::contains).toSet()
                mutableLink.value = NativePluginLink.Open
                welcomed.complete(connectionId!!)
                subscriptions.forEach { (key, sub) -> sendSubscription(key, sub) }
            }
            "ping" ->
                send(
                    buildJsonObject {
                        put("t", "pong")
                        raw["at"]?.let { put("at", it) }
                    },
                )
            "changed" -> mutableChanges.tryEmit(Unit)
            "done",
            "void" -> {
                val id = raw.npString("pending") ?: return
                val waiter = pending.remove(id)
                if (waiter != null) waiter.complete(raw)
                else {
                    val now = System.nanoTime()
                    early.entries.removeAll { now - it.value.first > 5_000_000_000L }
                    if (early.size >= 32) early.remove(early.keys.first())
                    early[id] = now to raw
                }
            }
            "data",
            "patch",
            "sync",
            "stale",
            "end",
            "micro.data",
            "micro.end" -> {
                val key = raw.npString("sub") ?: return
                val sub = subscriptions[key] ?: return
                val old = sub.state.value
                when (raw.npString("t")) {
                    "data" ->
                        sub.state.value =
                            NativePluginData(
                                raw["data"]?.toToolPayload() ?: ToolPayload.Null,
                                raw.npString("rev"),
                            )
                    "patch" ->
                        if (raw.npString("base") == old.rev && old.rev != null)
                            sub.state.value =
                                NativePluginData(
                                    nativePluginMerge(
                                        old.data,
                                        raw["merge"]?.toToolPayload() ?: ToolPayload.Null,
                                    ),
                                    raw.npString("rev"),
                                )
                        else sendSubscription(key, sub)
                    "stale" -> sub.state.value = old.copy(stale = true)
                    "end",
                    "micro.end" -> {
                        subscriptions.remove(key)
                        sub.state.value = old.copy(ended = raw.npString("reason") ?: "error")
                    }
                    "micro.data" -> {
                        if (!sub.micro || raw.toString().toByteArray().size > 4096) return
                        val data = raw["data"] as? JsonObject ?: return
                        val rev = raw.npString("rev") ?: return
                        if (
                            data.keys.any { it !in setOf("text", "progress") } ||
                                data.toString().toByteArray().size > 1024 ||
                                (data.npString("text")?.length ?: 0) > 240
                        )
                            return
                        val progress = (data["progress"] as? JsonPrimitive)?.doubleOrNull
                        if (progress != null && (!progress.isFinite() || progress !in 0.0..1.0))
                            return
                        val match =
                            Regex("([0-9a-f]{16})\\.([1-9][0-9]{0,15})").matchEntire(rev) ?: return
                        old.rev?.let { previous ->
                            val before = previous.substringAfter('.').toLongOrNull() ?: return@let
                            if (
                                previous.substringBefore('.') != match.groupValues[1] ||
                                    match.groupValues[2].toLong() <= before
                            )
                                return
                        }
                        sub.state.value = NativePluginData(data.toToolPayload(), rev)
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        subscriptions.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
        early.clear()
        mutableLink.value = NativePluginLink.Closed
    }
}

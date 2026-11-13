package dev.fughetta.dshcompose.data.plugins

import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.data.nativeplugins.*
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.engine.BridgeClientInventory
import dev.fughetta.dshcompose.domain.engine.WebBootGraph
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.plugins.PluginUiRepository
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Client-surface resources owned independently of the host plugin repository. */
class EnginePluginUiRepository(
    client: ProtocolClient,
    private val connectionActive: () -> Boolean = { true },
) : PluginUiRepository {
    private val nativeBindings = mutableSetOf<EnginePluginUiRepository>()
    private var disposeBinding: (() -> Unit)? = null
    private val nativeBridge = lazy { NativePluginApi(this.client) }
    private var nativeSocket: NativePluginStream? = null

    override val generation: ConnectionGeneration?
        get() = connectionGeneration

    override val nativeActive: Boolean
        get() =
            !closed &&
                connectionActive() &&
                source.mux.state.value.generation == connectionGeneration

    override val supportsNativePlugins: Boolean
        get() = source.connection is HostConnection

    override fun openNative(caps: NativePluginCaps, parent: CoroutineScope): PluginUiRepository =
        synchronized(nativeBindings) {
            if (!nativeActive || !supportsNativePlugins)
                throw CancellationException("Native plugin connection retired")
            val binding = EnginePluginUiRepository(client) { nativeActive }
            binding.nativeSocket = NativePluginStream(binding.nativeBridge.value, caps, parent)
            binding.disposeBinding = {
                synchronized(nativeBindings) { nativeBindings.remove(binding) }
            }
            nativeBindings.add(binding)
            binding
        }

    private fun native(): NativePluginApi {
        if (!nativeActive) throw CancellationException("Native plugin connection retired")
        return nativeBridge.value
    }

    private fun socket(): NativePluginStream {
        if (!nativeActive) throw CancellationException("Native plugin connection retired")
        return checkNotNull(nativeSocket)
    }

    override val link: StateFlow<NativePluginLink>
        get() = socket().link

    override val changes: Flow<Unit>
        get() = socket().changes

    override val connectionId: String?
        get() = nativeSocket?.connectionId

    override val negotiatedFeatures: Set<String>
        get() = nativeSocket?.negotiatedFeatures.orEmpty()

    override fun foreground(value: Boolean) = socket().foreground(value)

    override fun subscribe(
        pkg: String,
        id: String,
        context: NativePluginScope,
        data: ToolPayload,
        rev: String?,
        micro: Boolean,
    ): NativePluginFeed = socket().subscribe(pkg, id, context, data, rev, micro)

    override suspend fun hold(): String = socket().hold()

    override fun release() {
        nativeSocket?.release()
    }

    override fun selectionHold(): AutoCloseable = socket().selectionHold()

    override fun capabilityEvent(lease: String, seq: Int, event: String, reason: String?) {
        nativeSocket?.capabilityEvent(lease, seq, event, reason)
    }

    override suspend fun contributions(
        caps: NativePluginCaps,
        previous: NativePluginContributions?,
    ): NativePluginContributions? = native().contributions(caps, previous)

    override suspend fun asset(pkg: String, path: String): ByteArray = native().asset(pkg, path)

    override suspend fun media(
        pkg: String,
        id: String,
        lease: String,
        key: String?,
    ): Pair<ByteArray, String> = native().media(pkg, id, lease, key)

    override suspend fun uploadPickedFile(
        request: String,
        stream: String,
        name: String,
        mediaType: String,
        bytes: ByteArray,
    ) = native().uploadPickedFile(request, stream, name, mediaType, bytes)

    override suspend fun completeSelection(
        request: String,
        stream: String,
        status: String,
    ): NativePluginResult = nativeResult(native().completeSelection(request, stream, status))

    override suspend fun invokeNative(
        static: List<ToolPayload.Obj>,
        request: (String) -> NativePluginInvocation,
        consume: suspend (String?, NativePluginResult) -> Unit,
    ) {
        val bridge = native()
        val stream = socket()
        withNativePluginInvocation(bridge, stream, static, request) { connection, body ->
            consume(connection, nativeResult(body))
        }
    }

    override fun panel(
        pkg: String,
        id: String,
        scope: NativePluginScope,
        caps: NativePluginCaps,
        micro: String?,
    ): Flow<NativePanelUpdate> = nativePluginPanel(native(), socket(), pkg, id, scope, caps, micro)

    private fun nativeResult(body: JsonObject): NativePluginResult =
        NativePluginResult(
            nativePluginEffects(body["effects"]).map { it.toToolPayload() as ToolPayload.Obj },
            body.npObjects("issues").map { it.npString("code") ?: "invalid-effect" },
        )

    private val source = client
    @Volatile private var closed = false
    private val client: ProtocolClient
        get() {
            if (closed) throw ConnectionClosedException()
            return source
        }

    private fun active() = !closed && connectionActive()

    private val connectionGeneration = source.mux.state.value.generation

    override fun close() {
        val bindings =
            synchronized(nativeBindings) {
                if (closed) return
                closed = true
                nativeBindings.toList().also { nativeBindings.clear() }
            }
        bindings.forEach { it.close() }
        nativeSocket?.close()
        nativeSocket = null
        if (nativeBridge.isInitialized()) nativeBridge.value.close()
        disposeBinding?.invoke()
        disposeBinding = null
    }

    override val webReachable: Boolean
        get() = source.connection is HostConnection

    override suspend fun bootGraph(): WebBootGraph? =
        client.withGeneration(connectionGeneration, ::active) { client.readWebBootGraph() }

    override suspend fun bridgeInventory(): BridgeClientInventory =
        client.withGeneration(connectionGeneration, ::active) { client.readBridgeClientInventory() }
}

package dev.fughetta.dshcompose.domain.plugins

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.engine.BridgeClientInventory
import dev.fughetta.dshcompose.domain.engine.WebBootGraph
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.nativeplugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Optional client surfaces. Host plugin operations do not depend on this interface. */
interface PluginUiRepository : AutoCloseable {
    val generation: ConnectionGeneration?
    val nativeActive: Boolean
    val supportsNativePlugins: Boolean

    /** An independently retired native binding for one page's capabilities and locale. */
    fun openNative(caps: NativePluginCaps, parent: CoroutineScope): PluginUiRepository

    val link: StateFlow<NativePluginLink>
    val changes: Flow<Unit>
    val connectionId: String?
    val negotiatedFeatures: Set<String>

    fun foreground(value: Boolean)

    fun subscribe(
        pkg: String,
        id: String,
        context: NativePluginScope,
        data: ToolPayload = ToolPayload.Null,
        rev: String? = null,
        micro: Boolean = false,
    ): NativePluginFeed

    suspend fun hold(): String

    fun release()

    fun selectionHold(): AutoCloseable

    fun capabilityEvent(lease: String, seq: Int, event: String, reason: String? = null)

    suspend fun contributions(
        caps: NativePluginCaps,
        previous: NativePluginContributions? = null,
    ): NativePluginContributions?

    suspend fun asset(pkg: String, path: String): ByteArray

    suspend fun media(pkg: String, id: String, lease: String, key: String?): Pair<ByteArray, String>

    suspend fun uploadPickedFile(
        request: String,
        stream: String,
        name: String,
        mediaType: String,
        bytes: ByteArray,
    )

    suspend fun completeSelection(
        request: String,
        stream: String,
        status: String,
    ): NativePluginResult

    suspend fun invokeNative(
        static: List<ToolPayload.Obj>,
        request: (String) -> NativePluginInvocation,
        consume: suspend (String?, NativePluginResult) -> Unit,
    )

    fun panel(
        pkg: String,
        id: String,
        scope: NativePluginScope,
        caps: NativePluginCaps,
        micro: String?,
    ): Flow<NativePanelUpdate>

    /** True when the host serves the engine's web console on a connection the app can reach. */
    val webReachable: Boolean

    /** Null when the host predates the bridge or has no boot graph. */
    suspend fun bootGraph(): WebBootGraph?

    suspend fun bridgeInventory(): BridgeClientInventory
}

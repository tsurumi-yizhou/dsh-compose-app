package dev.fughetta.dshcompose.data.nativeplugins

import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

@OptIn(FlowPreview::class)
fun nativePluginPanel(
    bridge: NativePluginApi,
    socket: NativePluginStream,
    pkg: String,
    id: String,
    scope: NativePluginScope,
    caps: NativePluginCaps,
    micro: String?,
): Flow<NativePanelUpdate> = channelFlow {
    var panelFeed: NativePluginFeed? = null
    var microFeed: NativePluginFeed? = null
    try {
        val view = bridge.view(pkg, id, scope, caps)
        send(NativePanelUpdate.Snapshot(view))
        if (view.live) {
            val feed = socket.subscribe(pkg, id, scope, view.data, view.rev).also { panelFeed = it }
            launch { feed.state.collect { send(NativePanelUpdate.Patch(it)) } }
        }
        if (micro != null) {
            val feed = socket.subscribe(pkg, micro, scope, micro = true).also { microFeed = it }
            launch {
                feed.state.sample(100).collect {
                    send(NativePanelUpdate.Micro(it.data.text("text")))
                }
            }
        }
        awaitCancellation()
    } finally {
        panelFeed?.close()
        microFeed?.close()
    }
}

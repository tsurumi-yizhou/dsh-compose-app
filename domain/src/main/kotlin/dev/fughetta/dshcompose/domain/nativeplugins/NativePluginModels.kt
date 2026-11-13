package dev.fughetta.dshcompose.domain.nativeplugins

import dev.fughetta.dshcompose.domain.chat.ToolPayload

data class NativePluginScope(
    val sessionId: String? = null,
    val turnId: String? = null,
    val messageId: String? = null,
    val callId: String? = null,
)

data class NativePluginCaps(
    val app: String,
    val locale: String,
    val components: Set<String>,
    val features: Set<String> = emptySet(),
)

data class NativePluginAction(
    val pkg: String,
    val id: String,
    val label: String,
    val surface: String,
    val effects: List<ToolPayload.Obj> = emptyList(),
)

data class NativePluginPanel(
    val pkg: String,
    val id: String,
    val title: String,
    val placements: List<String>,
    val live: Boolean,
    val sheet: Boolean,
    val micro: String?,
)

data class NativePlugin(
    val pkg: String,
    val title: String,
    val wants: Set<String>,
    val capabilities: Set<String>,
    val actions: List<NativePluginAction>,
    val panels: List<NativePluginPanel>,
    val strips: List<ToolPayload.Obj>,
    val turnNotes: List<String>,
    val media: Set<String>,
    val overlays: List<ToolPayload.Obj>,
    val raw: ToolPayload.Obj,
    val dependencies: Set<String> = emptySet(),
)

data class NativePluginContributions(
    val rev: String?,
    val plugins: List<NativePlugin>,
    val raw: ToolPayload.Obj,
)

data class NativePluginView(
    val view: ToolPayload,
    val data: ToolPayload,
    val rev: String?,
    val live: Boolean,
    val raw: ToolPayload.Obj,
)

data class NativePluginInvocation(
    val pkg: String,
    val id: String,
    val surface: String,
    val scope: NativePluginScope,
    val action: String? = null,
    val value: ToolPayload? = null,
    val context: ToolPayload.Obj = ToolPayload.Obj(emptyMap()),
    val locale: String,
    val stream: String?,
    val userGesture: Boolean = true,
)

data class NativePluginData(
    val data: ToolPayload,
    val rev: String?,
    val stale: Boolean = false,
    val ended: String? = null,
)

enum class NativePluginLink {
    Idle,
    Connecting,
    Open,
    Backoff,
    Background,
    Closed,
}

fun nativePluginId(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))

fun nativePluginMerge(target: ToolPayload, patch: ToolPayload): ToolPayload {
    if (patch !is ToolPayload.Obj) return patch
    val result = (target as? ToolPayload.Obj)?.fields?.toMutableMap() ?: mutableMapOf()
    patch.fields.forEach { (key, value) ->
        if (value == ToolPayload.Null) result.remove(key)
        else result[key] = nativePluginMerge(result[key] ?: ToolPayload.Null, value)
    }
    return ToolPayload.Obj(result)
}

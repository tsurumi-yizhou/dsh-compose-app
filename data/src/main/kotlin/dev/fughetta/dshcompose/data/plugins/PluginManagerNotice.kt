package dev.fughetta.dshcompose.data.plugins

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.plugins.PluginManagerNotice

fun pluginManagerNotice(event: ToolPayload.Obj): PluginManagerNotice? {
    val type = event.text("event") ?: return null
    if (event.text("type") != "emit" || !type.startsWith("plugin-manager/")) return null
    val raw =
        (event.fields["args"] as? ToolPayload.Arr)?.items?.firstOrNull() as? ToolPayload.Obj
            ?: ToolPayload.Obj(emptyMap())
    return PluginManagerNotice(
        type,
        raw.text("requestId"),
        raw.text("phase"),
        raw.text("text"),
        raw.text("stream"),
        raw.text("jobId"),
        raw.fields["attempt"] as? ToolPayload.Obj,
    )
}

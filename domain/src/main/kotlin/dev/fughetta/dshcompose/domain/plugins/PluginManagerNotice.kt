package dev.fughetta.dshcompose.domain.plugins

import dev.fughetta.dshcompose.domain.chat.ToolPayload

data class PluginManagerNotice(
    val type: String,
    val requestId: String?,
    val phase: String?,
    val text: String?,
    val stream: String?,
    val jobId: String?,
    val attempt: ToolPayload.Obj?,
)

package dev.fughetta.dshcompose.domain.nativeplugins

import dev.fughetta.dshcompose.domain.chat.ToolPayload

data class NativePluginResult(val effects: List<ToolPayload.Obj>, val issues: List<String>)

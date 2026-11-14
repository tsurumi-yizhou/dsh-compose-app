package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.sessions.TranscriptTurnTrace
import dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex
import dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState

sealed interface MessageRenderContext {
    data class Message(
        val sessionId: String,
        val row: ChatItem,
        val referenceLabels: ReferenceLabelIndex,
        val onMenu: () -> Unit,
        val onImage: (ChatAttachment) -> Unit,
        val cardOnly: Boolean,
        val onAction: (AppAction) -> Unit,
        val onRetry: (() -> Unit)?,
    ) : MessageRenderContext

    data class Turn(
        val trace: TranscriptTurnTrace,
        val onOpen: () -> Unit,
        val plugins: NativePluginsState,
    ) : MessageRenderContext
}

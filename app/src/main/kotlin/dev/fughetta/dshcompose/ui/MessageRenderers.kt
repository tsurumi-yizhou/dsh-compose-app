package dev.fughetta.dshcompose.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.nativeplugins.NativePlugin
import dev.fughetta.dshcompose.domain.sessions.TranscriptTurnTrace
import dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex
import dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState
import dev.fughetta.dshcompose.ui.transcript.TranscriptFootnote

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

internal val builtInMessageRenderers =
    listOf(
        AppModuleContribution.MessageRenderer(
            "app:message",
            { it is MessageRenderContext.Message },
        ) {
            val message = it as MessageRenderContext.Message
            BuiltInMessage(
                message.sessionId,
                message.row,
                message.referenceLabels,
                message.onMenu,
                message.onImage,
                message.cardOnly,
                message.onAction,
                message.onRetry,
            )
        },
        AppModuleContribution.MessageRenderer("app:turn", { it is MessageRenderContext.Turn }) {
            val turn = it as MessageRenderContext.Turn
            TranscriptFootnote(turn.trace, turn.onOpen)
        },
    )

val LocalMessageRenderers = staticCompositionLocalOf { builtInMessageRenderers }

@Composable
internal fun RegisteredMessage(context: MessageRenderContext) {
    LocalMessageRenderers.current
        .filter { it.accepts(context) }
        .forEach { renderer -> key(renderer.id) { renderer.render(context) } }
}

@Composable
internal fun ChatMessage(
    sessionId: String,
    row: ChatItem,
    referenceLabels: ReferenceLabelIndex,
    onMenu: () -> Unit,
    onImage: (ChatAttachment) -> Unit,
    cardOnly: Boolean = false,
    onAction: (AppAction) -> Unit,
    onRetry: (() -> Unit)? = null,
) =
    RegisteredMessage(
        MessageRenderContext.Message(
            sessionId,
            row,
            referenceLabels,
            onMenu,
            onImage,
            cardOnly,
            onAction,
            onRetry,
        ),
    )

internal fun nativeTurnNoteRenderer(plugin: NativePlugin) =
    AppModuleContribution.MessageRenderer(
        "plugin:${plugin.pkg}:turn-notes",
        {
            it is MessageRenderContext.Turn &&
                it.plugins.enabled(plugin.pkg, "turn.note") &&
                it.plugins.turnNotes[it.trace.id].orEmpty().any { note -> note.pkg == plugin.pkg }
        },
    ) {
        val turn = it as MessageRenderContext.Turn
        Text(
            turn.plugins.turnNotes[turn.trace.id]
                .orEmpty()
                .filter { note -> note.pkg == plugin.pkg }
                .joinToString(" · ") { note -> note.text },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

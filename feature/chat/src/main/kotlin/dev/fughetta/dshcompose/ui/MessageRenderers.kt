package dev.fughetta.dshcompose.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.nativeplugins.NativePlugin
import dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex
import dev.fughetta.dshcompose.ui.transcript.TranscriptFootnote

val builtInMessageRenderers =
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

fun nativeTurnNoteRenderer(plugin: NativePlugin) =
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

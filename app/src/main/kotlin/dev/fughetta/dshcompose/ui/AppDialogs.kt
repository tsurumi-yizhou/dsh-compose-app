package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import dev.fughetta.dshcompose.ui.sessions.SessionDialogRoute
import dev.fughetta.dshcompose.ui.sessions.SessionDialogState
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun BoxScope.AppDialogs(
    state: AppState,
    conversationState: StateFlow<ConversationsSnapshot>,
    sessionOperations: StateFlow<SessionDialogState?>,
    messageMenus: StateFlow<dev.fughetta.dshcompose.ui.messages.MessageMenuState?>,
    imagePreview: StateFlow<dev.fughetta.dshcompose.ui.images.ImagePreviewState?>,
    modelPicker: StateFlow<dev.fughetta.dshcompose.ui.models.ModelPickerState?>,
    platformAppearance: @Composable BoxScope.() -> Unit,
    backStack: MutableList<NavKey>,
    onAction: (AppAction) -> Unit,
) {
    dev.fughetta.dshcompose.ui.nativeplugins.NativePluginConsentDialog(state.nativePlugins) {
        onAction(AppAction.NativePluginConsent(it))
    }
    dev.fughetta.dshcompose.ui.notify.NotificationBannerHost(
        state.notificationBanner,
        onOpen = { onAction(AppAction.OpenNotificationBanner) },
        onDismiss = { onAction(AppAction.DismissNotificationBanner) },
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
    )
    platformAppearance()
    state.imageExport?.takeIf { it.share == null }?.let { ImageExportDialog(it, onAction) }
    dev.fughetta.dshcompose.ui.images.ImagePreviewRoute(imagePreview) {
        onAction(AppAction.CloseImage)
    }
    dev.fughetta.dshcompose.ui.media.GenerationParameters(state.mediaGeneration) {
        onAction(AppAction.Media(it))
    }
    dev.fughetta.dshcompose.ui.media.GeneratedMediaViewer(state.mediaGeneration) {
        onAction(AppAction.Media(it))
    }
    dev.fughetta.dshcompose.ui.media.MediaDeleteConfirmation(state.mediaGeneration) {
        onAction(AppAction.Media(it))
    }
    dev.fughetta.dshcompose.ui.transcript.TranscriptUsageSheet(
        state.usage,
        onAction = { onAction(AppAction.Usage(it)) },
        onTopUp = { onAction(AppAction.TopUpBalance) },
    )
    dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactStatus(state.transcriptArtifact)
    dev.fughetta.dshcompose.ui.transcript.TranscriptSheetDialog(
        state.transcriptArtifact.sheet,
        onSelect = { onAction(AppAction.TranscriptSheet(it)) },
        onRefresh = { onAction(AppAction.RefreshTranscriptTable) },
        onDismiss = { onAction(AppAction.CloseTranscriptTable) },
    )
    state.sessionArtifacts?.let { sessionId ->
        collectConversation(conversationState, sessionId)?.let { chat ->
            dev.fughetta.dshcompose.ui.transcript.TranscriptAllArtifactsSheet(
                chat.turns,
                onAction = { file, action ->
                    onAction(AppAction.TranscriptArtifact(sessionId, file, action))
                },
                onDismiss = { onAction(AppAction.CloseSessionArtifacts) },
                hiddenPaths = chat.generatedMediaPaths,
            )
        }
    }
    dev.fughetta.dshcompose.ui.messages.MessageMenuRoute(messageMenus) { menu ->
        dev.fughetta.dshcompose.ui.messages.MessageMenu(
            menu,
            onAction = { onAction(AppAction.MessageOperation(it)) },
            onClose = { onAction(AppAction.CloseMessageMenu) },
        ) {
            val row = menu.row
            for (action in
                dev.fughetta.dshcompose.ui.nativeplugins.nativePluginActions(
                    state.nativePlugins,
                    "message.actions",
                )) TextButton(
                onClick = {
                    onAction(AppAction.CloseMessageMenu)
                    onAction(
                        AppAction.NativePluginInvoke(
                            action,
                            dev.fughetta.dshcompose.domain.nativeplugins.NativePluginScope(
                                menu.sessionId,
                                row.turnId,
                                row.messageId,
                                row.tool?.callId,
                            ),
                            row.text,
                        ),
                    )
                },
                enabled =
                    !menu.busy &&
                        "${action.pkg}|message.actions|${action.id}" !in state.nativePlugins.busy,
            ) {
                Text(action.label)
            }
        }
    }
    SessionDialogRoute(sessionOperations, onAction)
    state.replyLink?.let { ReplyLinkDialog(it, onAction) }
    state.goal?.let {
        if (it.dialog || it.editor != null || it.clearing != null) GoalDialog(it, onAction)
    }
    state.sessionExport?.let { SessionExportDialog(it, onAction) }
    state.skills?.let { SkillsDialog(it, onAction) }
    state.jobs?.let { JobsDialog(it, onAction) }
    state.workspaceFiles?.let { WorkspaceFilesDialog(it, onAction) }
    state.references?.takeIf { !it.inline }?.let { ReferencesDialog(it, onAction) }
    state.workspaceExport?.let { WorkspaceExportDialog(it, onAction) }
    state.workspaces.sessionDialog?.let { WorkspaceSessionDialog(it, onAction) }
    state.workspaces.dialog?.let { WorkspaceDialog(it, onAction) }
    state.directoryPicker?.let { DirectoryPickerDialog(it, onAction) }
    ModelPickerRoute(
        modelPicker,
        onAction,
        onProviders = {
            onAction(AppAction.CloseModelPicker)
            backStack.add(Providers)
        },
    )
}

package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.SessionCatalogState
import dev.fughetta.dshcompose.ui.commands.CommandsUiState
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import dev.fughetta.dshcompose.ui.sessions.DrawerUiState
import dev.fughetta.dshcompose.ui.sessions.SessionDeletionState
import dev.fughetta.dshcompose.ui.sessions.SessionDialogState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable internal data class Chat(val id: String) : NavKey

@Serializable internal data class SessionDetail(val id: String) : NavKey

@Serializable internal data object About : NavKey

@Serializable internal data object Licenses : NavKey

@Serializable internal data object Workspaces : NavKey

@Serializable internal data object SessionSearch : NavKey

@Serializable internal data object AgentPresets : NavKey

@Serializable internal data object Schedules : NavKey

@Serializable internal data object DeepSeekAccount : NavKey

@Serializable internal data object Permissions : NavKey

@Serializable internal data object MediaSettings : NavKey

@Serializable internal data object MediaWorks : NavKey

@Serializable internal data object Agents : NavKey

@Serializable internal data object HostServices : NavKey

@Serializable internal data object Terminal : NavKey

@Serializable internal data object NotificationSettings : NavKey

@Serializable internal data object NativePluginPreferences : NavKey

@Serializable internal data object PluginSeats : NavKey

@Serializable internal data object NativePluginPage : NavKey

@Serializable internal data object EngineConfiguration : NavKey

@Serializable internal data object Cordis : NavKey

@Serializable internal data object ReplyPrefs : NavKey

@Serializable internal data object SettingsSearch : NavKey

@Serializable internal data object QuickPanelSettings : NavKey

/** Pages a feature id or a page link (`dsh://open/<id>`) may open. */
internal fun featureDestination(
    page: String,
    entries: List<AppModuleContribution.Page> = builtInPages,
): NavKey? =
    (entries.firstOrNull { page in it.ids }?.target as? PageTarget.Navigation)
        ?.destination
        ?.invoke()

fun opensFeaturePage(page: String): Boolean = featureDestination(page) != null

/**
 * Puts the [home] conversation back under a stack with no conversation left in it (the host
 * changed, or setup finished on its own page), so back returns home instead of leaving the app. The
 * finished setup page at the bottom gives way to home.
 */
internal fun rootAtHome(backStack: MutableList<NavKey>, home: Chat, expanded: Boolean) {
    if (backStack.any { it is Chat }) return
    if (backStack.firstOrNull() == Engine) backStack.removeAt(0)
    backStack.removeAll { it == Conversations }
    backStack.add(0, home)
    if (expanded) backStack.add(0, Conversations)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppShell(
    state: AppState,
    commandPalette: StateFlow<CommandsUiState?>,
    conversationState: StateFlow<ConversationsSnapshot>,
    sessionSearch: StateFlow<SessionSearchUiState>,
    sessionCatalog: StateFlow<SessionCatalogState>,
    drawerSource: StateFlow<DrawerUiState>,
    deletionSource: StateFlow<SessionDeletionState>,
    sessionOperations: StateFlow<SessionDialogState?>,
    messageMenus: StateFlow<dev.fughetta.dshcompose.ui.messages.MessageMenuState?>,
    imagePreview: StateFlow<dev.fughetta.dshcompose.ui.images.ImagePreviewState?>,
    modelPicker: StateFlow<dev.fughetta.dshcompose.ui.models.ModelPickerState?>,
    modelDefaults: StateFlow<dev.fughetta.dshcompose.ui.models.ModelDefaultsUiState>,
    providers: StateFlow<dev.fughetta.dshcompose.ui.providers.ProvidersUiState>,
    subscriptions: StateFlow<dev.fughetta.dshcompose.ui.providers.SubscriptionsUiState>,
    agentPresets: StateFlow<AgentPresetsUiState>,
    agents: StateFlow<dev.fughetta.dshcompose.ui.agents.AgentsState>,
    imageLoader: coil3.ImageLoader? = null,
    codeHighlighter: dev.fughetta.dshcompose.ui.markdown.CodeHighlighter? = null,
    claimPhonePermission: (String) -> Boolean,
    loadNativePluginAsset: suspend (String, String) -> ByteArray?,
    claimNotificationPermission: (String) -> Boolean,
    reasoningTranslation: StateFlow<dev.fughetta.dshcompose.domain.messages.ReasoningTranslation?>,
    platformAppearance: @Composable BoxScope.() -> Unit,
    onAction: (AppAction) -> Unit,
) {
    val reasoning by reasoningTranslation.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalFeaturePages provides state.featurePages,
        LocalMessageRenderers provides state.messageRenderers,
        LocalComposerActions provides state.composerActions,
        dev.fughetta.dshcompose.ui.shell.LocalFeatureCatalog provides state.featureCatalog,
        dev.fughetta.dshcompose.ui.phone.LocalPerformanceSettings provides
            state.performanceSettings,
        dev.fughetta.dshcompose.ui.reasoning.LocalReasoningTranslation provides reasoning,
        dev.fughetta.dshcompose.ui.markdown.LocalReplyImages provides
            dev.fughetta.dshcompose.ui.markdown.ReplyImageContext(
                imageLoader,
                "",
                state.imageGeneration,
            ),
    ) {
        AppShellContent(
            state,
            commandPalette,
            conversationState,
            sessionSearch,
            sessionCatalog,
            drawerSource,
            deletionSource,
            sessionOperations,
            messageMenus,
            imagePreview,
            modelPicker,
            modelDefaults,
            providers,
            subscriptions,
            agentPresets,
            agents,
            imageLoader,
            codeHighlighter,
            claimPhonePermission,
            loadNativePluginAsset,
            claimNotificationPermission,
            platformAppearance,
            onAction,
        )
    }
}

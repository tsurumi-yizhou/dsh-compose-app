package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.SessionCatalogState
import dev.fughetta.dshcompose.ui.adaptive.atLeastLarge
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import dev.fughetta.dshcompose.ui.commands.CommandsUiState
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.conversation.ChatUiState
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import dev.fughetta.dshcompose.ui.sessions.DrawerUiState
import dev.fughetta.dshcompose.ui.sessions.SessionDeletionState
import dev.fughetta.dshcompose.ui.sessions.SessionDialogState
import dev.fughetta.dshcompose.ui.sessions.SessionsDrawerRoute
import dev.fughetta.dshcompose.ui.shell.quickPanelSwipe
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AppShellContent(
    appState: AppState,
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
    imageLoader: coil3.ImageLoader?,
    codeHighlighter: dev.fughetta.dshcompose.ui.markdown.CodeHighlighter?,
    claimPhonePermission: (String) -> Boolean,
    loadNativePluginAsset: suspend (String, String) -> ByteArray?,
    claimNotificationPermission: (String) -> Boolean,
    platformAppearance: @Composable BoxScope.() -> Unit,
    onAction: (AppAction) -> Unit,
) {
    // Navigation retains entry content. Read the live state inside those compositions.
    val currentState = rememberUpdatedState(appState)
    val state by currentState
    AppTheme(
        state.uiThemeMode ?: state.theme,
        state.dynamicColor,
        state.appearance.preferences.takeUnless { state.appearance.loading },
    ) {
        dev.fughetta.dshcompose.ui.services.RemoteHostDialog(state, onAction)
        val backStack =
            rememberNavBackStack(
                if (state.engineControls.onboardingComplete) Chat(DRAFT_SESSION_ID) else Engine,
            )
        val expanded by
            rememberUpdatedState(
                currentWindowAdaptiveInfoV2()
                    .windowSizeClass
                    .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND),
            )
        // The session details only earn a third column on Large windows. Narrower ones keep the
        // overlay/single pane, so the transcript never has to share its width three ways.
        val threePane by rememberUpdatedState(windowTier().atLeastLarge)
        // [id]: where to land when the view model hasn't caught up with a selection just made.
        val goHome =
            remember(backStack) {
                { id: String? ->
                    backStack.clear()
                    if (expanded) backStack.add(Conversations)
                    backStack.add(Chat(id ?: state.selectedSessionId ?: DRAFT_SESSION_ID))
                }
            }
        val context = androidx.compose.ui.platform.LocalContext.current
        val askAiText =
            dev.fughetta.dshcompose.ui.shell.rememberAskAiText(state.hostIdentity == "local")
        val latestAskAiText by rememberUpdatedState(askAiText)
        // 问 AI: a new conversation with the question in its composer, not sent.
        val askAiQuestion =
            remember(goHome) {
                { question: String ->
                    onAction(AppAction.AskAi(latestAskAiText(question)))
                    goHome(DRAFT_SESSION_ID)
                    Unit
                }
            }
        val askAi =
            remember(askAiQuestion) {
                { topic: dev.fughetta.dshcompose.ui.shell.AskAiTopic ->
                    askAiQuestion(context.getString(topic.question))
                }
            }
        // Pages a feature contribution targets, opened as the settings page always did.
        val openPage = { page: FeaturePage ->
            if (page == FeaturePage.AskAi) {
                askAi(dev.fughetta.dshcompose.ui.shell.AskAiTopic.Overview)
            } else {
                (state.featurePages.firstOrNull { it.menu == page }?.target
                        as? PageTarget.Navigation)
                    ?.destination
                    ?.invoke()
                    ?.let {
                        val settingsIndex = backStack.indexOf(Settings)
                        if (expanded && settingsIndex >= 0) {
                            while (backStack.lastIndex > settingsIndex) backStack.removeAt(
                                backStack.lastIndex,
                            )
                        }
                        backStack.add(it)
                    }
            }
            Unit
        }
        // Feature ids whose page opens through an action rather than a destination.
        val openFeature = { id: String ->
            when (id) {
                "permissions" -> onAction(AppAction.OpenPermissions())
                "terminal" -> onAction(AppAction.OpenTerminal)
                "media" ->
                    onAction(
                        AppAction.Media(dev.fughetta.dshcompose.ui.media.MediaAction.Settings),
                    )
                else -> featureDestination(id, state.featurePages)?.let { backStack.add(it) }
            }
            Unit
        }
        LaunchedEffect(expanded) {
            if (expanded) {
                if (backStack.any { it is Chat } && backStack.firstOrNull() != Conversations)
                    backStack.add(0, Conversations)
            } else {
                backStack.removeAll { it == Conversations }
                if (backStack.isEmpty()) goHome(null)
            }
        }
        var displayedHost by rememberSaveable { mutableStateOf(state.hostIdentity) }
        LaunchedEffect(state.hostIdentity) {
            if (displayedHost != state.hostIdentity) {
                backStack.removeAll { it is Chat || it == PluginSeats }
                if (backStack.isEmpty()) goHome(null)
                else if (state.engineControls.onboardingComplete)
                    rootAtHome(
                        backStack,
                        Chat(state.selectedSessionId ?: DRAFT_SESSION_ID),
                        expanded,
                    )
                displayedHost = state.hostIdentity
            }
        }
        LaunchedEffect(state.engineControls.onboardingComplete) {
            if (state.engineControls.onboardingComplete && backStack.isNotEmpty())
                rootAtHome(backStack, Chat(state.selectedSessionId ?: DRAFT_SESSION_ID), expanded)
        }
        val strategy = rememberListDetailSceneStrategy<NavKey>()
        val current = backStack.lastOrNull()
        val open =
            remember(backStack) {
                { id: String ->
                    if (backStack.lastOrNull() != Chat(id)) {
                        if (backStack.contains(SessionSearch))
                            onAction(AppAction.DismissSessionSearch)
                        backStack.clear()
                        if (expanded) backStack.add(Conversations)
                        backStack.add(Chat(id))
                    }
                }
            }
        AppNavigationEvents(state, backStack, open, goHome, onAction)
        dev.fughetta.dshcompose.ui.appearance.AppearanceBackdrop(Modifier.fillMaxSize()) {
            val snackbar = remember { SnackbarHostState() }
            ErrorSnackbar(
                collectConversationError(conversationState, (current as? Chat)?.id) ?: state.error,
                snackbar,
            )
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val push = { key: NavKey ->
                scope.launch { drawerState.close() }
                if (backStack.lastOrNull() != key) backStack.add(key)
                Unit
            }
            val navigation = remember {
                DrawerNavigation(
                    onOpen = { id ->
                        scope.launch { drawerState.close() }
                        onAction(AppAction.SelectSession(id))
                    },
                    onNew = {
                        scope.launch { drawerState.close() }
                        onAction(AppAction.SelectSession(null))
                        goHome(DRAFT_SESSION_ID)
                    },
                    onSearch = { push(SessionSearch) },
                    onWorkspaces = { push(Workspaces) },
                    onEngine = { push(Engine) },
                    onHostServices = { push(HostServices) },
                    onSettings = { push(Settings) },
                )
            }
            val atHome by
                rememberUpdatedState(
                    current is Chat && backStack.firstOrNull { it != Conversations } == current,
                )
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = !expanded && atHome,
                drawerContent = {
                    if (!expanded)
                        // The drawer state lets the sheet consume back (with predictive back) while
                        // open.
                        ModalDrawerSheet(
                            drawerState,
                            modifier =
                                Modifier.width(
                                    (androidx.compose.ui.platform.LocalConfiguration.current
                                            .screenWidthDp
                                            .dp * .82f)
                                        .coerceAtMost(320.dp),
                                ),
                        ) {
                            SessionsDrawerRoute(
                                state,
                                sessionCatalog,
                                drawerSource,
                                deletionSource,
                                navigation,
                                onAction,
                            )
                        }
                },
            ) {
                var quickPanel by rememberSaveable { mutableStateOf(false) }
                Box(
                    Modifier.fillMaxSize().quickPanelSwipe(
                        atHome && state.shellPrefs.quickPanelSwipe,
                    ) {
                        quickPanel = true
                    },
                ) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = {
                            if (backStack.size > 1) {
                                if (backStack.lastOrNull() == SessionSearch)
                                    onAction(AppAction.DismissSessionSearch)
                                backStack.removeAt(backStack.lastIndex)
                            }
                        },
                        sceneStrategies = listOf(strategy),
                        transitionSpec = {
                            if (state.performanceSettings.animations)
                                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        popTransitionSpec = {
                            if (state.performanceSettings.animations)
                                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        predictivePopTransitionSpec = { _ ->
                            if (state.performanceSettings.animations)
                                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        entryProvider =
                            entryProvider {
                                entry<Conversations>(
                                    metadata =
                                        ListDetailSceneStrategy.listPane(
                                            detailPlaceholder = {
                                                Box(
                                                    Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        stringResource(
                                                            R.string.choose_conversation,
                                                        ),
                                                    )
                                                }
                                            },
                                        ),
                                ) {
                                    // The wide-window counterpart of the phone's ModalDrawerSheet;
                                    // fillMaxWidth lets the sheet take the whole list pane instead
                                    // of its 360dp cap.
                                    PermanentDrawerSheet(
                                        Modifier.fillMaxWidth(),
                                        drawerContainerColor =
                                            MaterialTheme.colorScheme.surfaceContainerLow,
                                        windowInsets = WindowInsets.safeDrawing,
                                    ) {
                                        SessionsDrawerRoute(
                                            state,
                                            sessionCatalog,
                                            drawerSource,
                                            deletionSource,
                                            navigation,
                                            onAction,
                                        )
                                    }
                                }
                                entry<SessionSearch> {
                                    val search by sessionSearch.collectAsStateWithLifecycle()
                                    SessionSearchScreen(
                                        search,
                                        state.workspaces,
                                        sessionCatalog.collectAsStateWithLifecycle().value.loading,
                                        onAction,
                                    )
                                }
                                entry<Chat>(metadata = ListDetailSceneStrategy.detailPane()) { key
                                    ->
                                    val chat = collectConversation(conversationState, key.id)
                                    LaunchedEffect(key.id) {
                                        if (key.id != DRAFT_SESSION_ID)
                                            onAction(AppAction.OpenSession(key.id))
                                    }
                                    CompositionLocalProvider(
                                        dev.fughetta.dshcompose.ui.markdown
                                            .LocalCodeHighlighter provides codeHighlighter,
                                        dev.fughetta.dshcompose.ui.markdown
                                            .LocalReplyImages provides
                                            dev.fughetta.dshcompose.ui.markdown.ReplyImageContext(
                                                imageLoader,
                                                key.id,
                                                state.imageGeneration,
                                            ),
                                        dev.fughetta.dshcompose.ui.markdown
                                            .LocalGeneratedMediaPaths provides
                                            (chat?.generatedMediaPaths ?: emptySet()),
                                        dev.fughetta.dshcompose.ui.markdown
                                            .LocalReplyLinkOpen provides
                                            { session, generation, target ->
                                                onAction(
                                                    AppAction.OpenReplyLink(
                                                        session,
                                                        generation,
                                                        target,
                                                    ),
                                                )
                                            },
                                        dev.fughetta.dshcompose.ui.markdown
                                            .LocalReplyImageExport provides
                                            { request, operation ->
                                                onAction(
                                                    AppAction.ExportReplyImage(request, operation),
                                                )
                                            },
                                    ) {
                                        val palette by commandPalette.collectAsStateWithLifecycle()
                                        ChatScreen(
                                            key.id,
                                            chat ?: ChatUiState(),
                                            state.workspaces,
                                            state.references?.takeIf {
                                                it.inline && it.sessionId == key.id
                                            },
                                            state.referenceLabels,
                                            palette?.takeIf { it.sessionId == key.id },
                                            state.goal?.takeIf { it.sessionId == key.id },
                                            agentPresets,
                                            state.nativePlugins,
                                            state.mediaGeneration,
                                            state.usage,
                                            state.backgroundNet,
                                            onMenu =
                                                if (!expanded && atHome) {
                                                    { scope.launch { drawerState.open() } }
                                                } else null,
                                            onDetails = {
                                                // The info button toggles the details pane.
                                                if (!backStack.remove(SessionDetail(key.id)))
                                                    backStack.add(SessionDetail(key.id))
                                            },
                                            onAction,
                                        )
                                    }
                                }
                                entry<SessionDetail>(
                                    metadata =
                                        if (threePane) ListDetailSceneStrategy.extraPane()
                                        else emptyMap(),
                                ) { key ->
                                    SessionDetailScreen(
                                        key.id,
                                        state.contributions,
                                        collectConversation(conversationState, key.id)?.syncing ==
                                            true,
                                        besideChat = threePane,
                                        onClose = { backStack.remove(key) },
                                        onAction,
                                        openPage,
                                    )
                                }
                                entry<Workspaces> {
                                    WorkspacesScreen(state.workspaces, onAction) { id ->
                                        onAction(AppAction.SelectWorkspace(id))
                                        goHome(null)
                                    }
                                }
                                entry<Engine>(
                                    metadata =
                                        if (Settings in backStack)
                                            ListDetailSceneStrategy.detailPane()
                                        else emptyMap(),
                                ) {
                                    dev.fughetta.dshcompose.ui.runtime.EngineControlsScreen(
                                        state.engineControls,
                                    ) {
                                        onAction(AppAction.EngineControls(it))
                                    }
                                }
                                entry<NativePluginPage>(
                                    metadata =
                                        if (Settings in backStack)
                                            ListDetailSceneStrategy.detailPane()
                                        else emptyMap(),
                                ) {
                                    var retainedPanel by remember {
                                        mutableStateOf(state.nativePlugins.panel)
                                    }
                                    val activePanel = state.nativePlugins.panel
                                    SideEffect {
                                        if (activePanel != null) retainedPanel = activePanel
                                    }
                                    DisposableEffect(Unit) {
                                        onDispose {
                                            if (NativePluginPage !in backStack)
                                                onAction(AppAction.CloseNativePluginPanel)
                                        }
                                    }
                                    dev.fughetta.dshcompose.ui.nativeplugins.NativePluginPanel(
                                        state.nativePlugins.copy(
                                            panel = activePanel ?: retainedPanel,
                                        ),
                                        onAction = {
                                            onAction(AppAction.NativePluginPanelAction(it))
                                        },
                                        onClose = {
                                            if (backStack.lastOrNull() == NativePluginPage)
                                                backStack.removeAt(backStack.lastIndex)
                                        },
                                        loadAsset = loadNativePluginAsset,
                                    )
                                }
                                settingsEntries(
                                    currentState,
                                    backStack,
                                    modelDefaults,
                                    providers,
                                    subscriptions,
                                    agents,
                                    agentPresets,
                                    sessionCatalog,
                                    claimPhonePermission,
                                    claimNotificationPermission,
                                    openPage,
                                    openFeature,
                                    goHome,
                                    askAi,
                                    askAiQuestion,
                                    onAction,
                                )
                            },
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    dev.fughetta.dshcompose.ui.shell.QuickPanel(
                        quickPanel,
                        state.shellPrefs.quickPanelItems,
                        onOpen = {
                            quickPanel = false
                            openFeature(it)
                        },
                        onEdit = {
                            quickPanel = false
                            backStack.add(QuickPanelSettings)
                        },
                        onDismiss = { quickPanel = false },
                    )
                }
            }
            AppDialogs(
                state,
                conversationState,
                sessionOperations,
                messageMenus,
                imagePreview,
                modelPicker,
                platformAppearance,
                backStack,
                onAction,
            )
        }
    }
}

package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.SessionCatalogState
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*
import dev.fughetta.dshcompose.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class,
)
internal fun androidx.navigation3.runtime.EntryProviderScope<NavKey>.settingsEntries(
    currentState: State<AppState>,
    backStack: MutableList<NavKey>,
    modelDefaults: StateFlow<dev.fughetta.dshcompose.ui.models.ModelDefaultsUiState>,
    providers: StateFlow<dev.fughetta.dshcompose.ui.providers.ProvidersUiState>,
    subscriptions: StateFlow<dev.fughetta.dshcompose.ui.providers.SubscriptionsUiState>,
    agents: StateFlow<dev.fughetta.dshcompose.ui.agents.AgentsState>,
    agentPresets: StateFlow<AgentPresetsUiState>,
    sessionCatalog: StateFlow<SessionCatalogState>,
    claimPhonePermission: (String) -> Boolean,
    claimNotificationPermission: (String) -> Boolean,
    openPage: (FeaturePage) -> Unit,
    openFeature: (String) -> Unit,
    goHome: (String?) -> Boolean,
    askAi: (dev.fughetta.dshcompose.ui.shell.AskAiTopic) -> Unit,
    askAiQuestion: (String) -> Unit,
    onAction: (AppAction) -> Unit,
) {
    val state by currentState
    val detailMetadata =
        if (Settings in backStack) ListDetailSceneStrategy.detailPane() else emptyMap()
    entry<Settings>(
        metadata =
            ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(stringResource(R.string.settings_choose_page))
                    }
                },
            ),
    ) {
        SettingsScreen(
            state,
            modelDefaults,
            onAction,
            openPage,
            onLanguage = {
                val index = backStack.indexOf(Settings)
                if (index >= 0)
                    while (backStack.lastIndex > index) backStack.removeAt(backStack.lastIndex)
                backStack.add(LanguageSettings)
            },
        )
    }
    entry<LanguageSettings>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.settings.LanguageScreen(state.language) {
            onAction(AppAction.ChangeLanguage(it))
        }
    }
    entry<DeepSeekAccount>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.account.DeepSeekAccountScreen(state.deepSeekAccount) {
            onAction(AppAction.DeepSeekAccount(it))
        }
    }
    entry<Appearance>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.appearance.AppearanceScreen(
            state.appearance,
            onAction = { onAction(AppAction.Appearance(it)) },
            mode = state.theme,
            onMode = { onAction(AppAction.ChangeTheme(it)) },
        )
    }
    entry<Permissions>(metadata = detailMetadata) {
        state.permissions?.let { PermissionsScreen(it, onAction) }
    }
    entry<MediaSettings>(metadata = detailMetadata) {
        DisposableEffect(Unit) {
            onDispose {
                onAction(
                    AppAction.Media(dev.fughetta.dshcompose.ui.media.MediaAction.CloseSettings),
                )
            }
        }
        dev.fughetta.dshcompose.ui.media.MediaSettingsScreen(state.mediaGeneration) {
            onAction(AppAction.Media(it))
        }
    }
    entry<MediaWorks>(metadata = detailMetadata) {
        DisposableEffect(Unit) {
            onDispose {
                onAction(
                    AppAction.Media(dev.fughetta.dshcompose.ui.media.MediaAction.CloseWorks),
                )
            }
        }
        dev.fughetta.dshcompose.ui.media.MediaWorksScreen(state.mediaGeneration) {
            onAction(AppAction.Media(it))
        }
    }
    entry<Agents>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.agents.AgentsRoute(
            agents,
            onAction = { onAction(AppAction.Agents(it)) },
        )
    }
    entry<HostServices>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.services.HostServicesScreen(
            state,
            onAction = onAction,
        )
    }
    entry<Terminal>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.terminal.TerminalScreen(
            state.terminal,
            onAction = { onAction(AppAction.Terminal(it)) },
        )
    }
    entry<NotificationSettings>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.notify.NotificationSettingsScreen(
            state.notificationSettings,
            onAction = { onAction(AppAction.NotificationSettings(it)) },
            claimPermission = claimNotificationPermission,
        )
    }
    entry<NativePluginPreferences>(metadata = detailMetadata) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(CoreDesignsystemR.string.navigation_native_plugins))
                    },
                    navigationIcon = { UpButton() },
                )
            },
        ) { padding ->
            ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = inset,
                            vertical = Spacing.Large,
                        ),
                ) {
                    dev.fughetta.dshcompose.ui.nativeplugins.NativePluginPreferences(
                        state.nativePlugins,
                        onSurface = { pkg, surface, enabled ->
                            onAction(
                                AppAction.NativePluginSurface(
                                    pkg,
                                    surface,
                                    enabled,
                                ),
                            )
                        },
                        onPanel = { pkg, id -> onAction(AppAction.NativePluginPanel(pkg, id)) },
                    )
                }
            }
        }
    }
    entry<PluginSeats>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.engine_settings.PluginSeatsPanel(
            state.pluginSeats,
            onAction = { onAction(AppAction.PluginSeats(it)) },
            sessionId = state.pluginSeatSession,
        )
    }
    entry<EngineConfiguration>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.engine_settings.EngineSettingsScreen(
            state.engineSettings,
            onAction = { onAction(AppAction.EngineConfiguration(it)) },
        )
    }
    entry<PluginManagement>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.engine_settings.PluginManagementScreen(
            state.pluginManagement,
            onAction = { onAction(AppAction.PluginManagement(it)) },
            onConfigure = { onAction(AppAction.ConfigurePlugin(it)) },
            onCordis = { backStack.add(Cordis) },
        )
    }
    entry<Cordis>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.engine_settings.CordisScreen(
            state.pluginManagement,
            sessionCatalog.collectAsStateWithLifecycle().value.sessions,
            state.selectedSessionId,
            onAction = { onAction(AppAction.PluginManagement(it)) },
        )
    }
    entry<Schedules>(metadata = detailMetadata) {
        SchedulesScreen(
            state.schedules,
            onAction = { onAction(AppAction.Schedules(it)) },
            onBack = {
                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else goHome(null)
            },
        )
    }
    entry<PhoneControls>(metadata = detailMetadata) { key ->
        dev.fughetta.dshcompose.ui.phone.PhoneControlsScreen(
            state.phoneControls,
            onAction = { onAction(AppAction.PhoneControls(it)) },
            claimPermission = claimPhonePermission,
            silencedRisks = state.shellPrefs.silencedRisks,
            onSilenceRisk = {
                onAction(
                    AppAction.ShellPrefs(
                        dev.fughetta.dshcompose.ui.shell.ShellPrefsAction.SilenceRisk(it),
                    ),
                )
            },
            onAskAi = askAi,
            initialSubpage = key.subpage,
            backgroundGuideSeen = state.shellPrefs.backgroundGuideSeen,
            onBackgroundGuideSeen = {
                onAction(
                    AppAction.ShellPrefs(
                        dev.fughetta.dshcompose.ui.shell.ShellPrefsAction.BackgroundGuideSeen,
                    ),
                )
            },
        )
    }
    entry<AgentPresets>(metadata = detailMetadata) { AgentPresetsRoute(agentPresets, onAction) }
    entry<Providers>(metadata = detailMetadata) {
        ProvidersRoute(
            providers,
            subscriptions,
            state.deepSeekAccount,
            onAction,
        )
    }
    entry<About>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.settings.AboutScreen { backStack.add(Licenses) }
    }
    entry<Licenses>(metadata = detailMetadata) {
        LaunchedEffect(Unit) { onAction(AppAction.LoadLicenses) }
        LicensesScreen(state)
    }
    entry<ReplyPrefs>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.personal.ReplyPrefsScreen(
            state.replyPrefs,
            onAction = { onAction(AppAction.ReplyPrefs(it)) },
            onSaved = {
                if (backStack.lastOrNull() == ReplyPrefs) backStack.removeAt(backStack.lastIndex)
            },
        )
    }
    entry<SettingsSearch>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.shell.SettingsSearchScreen(
            state.shellPrefs.recentSearches,
            onOpen = openFeature,
            onAction = { onAction(AppAction.ShellPrefs(it)) },
            onAskAi = askAiQuestion,
        )
    }
    entry<QuickPanelSettings>(metadata = detailMetadata) {
        dev.fughetta.dshcompose.ui.shell.QuickPanelSettingsScreen(state.shellPrefs) {
            onAction(AppAction.ShellPrefs(it))
        }
    }
}

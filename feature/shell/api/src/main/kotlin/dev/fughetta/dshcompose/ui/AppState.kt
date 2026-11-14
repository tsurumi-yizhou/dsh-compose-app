package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.hosts.LinkState
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.providers.*
import dev.fughetta.dshcompose.ui.schedules.*
import kotlinx.collections.immutable.*

data class AppState(
    val contributions: FeatureContributions = FeatureContributions(),
    val featureCatalog: List<dev.fughetta.dshcompose.ui.shell.FeatureEntry> =
        dev.fughetta.dshcompose.ui.shell.FeatureCatalog,
    val composerActions: List<AppModuleContribution.ComposerAction> = emptyList(),
    val messageRenderers: List<AppModuleContribution.MessageRenderer> = emptyList(),
    val featurePages: List<AppModuleContribution.Page> = emptyList(),
    // Chat updates must not compare every visited chat and its message list.
    val imageExport: ImageExportUiState? = null,
    val replyLink: ReplyLinkUiState? = null,
    val imageGeneration: Long = 0,
    val licenseDocuments: List<LicenseDocument>? = null,
    val licensesError: String? = null,
    val selectedSessionId: String? = null,
    val draftSessionId: String = DRAFT_SESSION_ID,
    val sessionAttention: SessionAttentionState = SessionAttentionState(),
    val openSessionId: String? = null,
    val openSessionRevision: Long = 0,
    val deletedSessionId: String? = null,
    val directoryPicker: DirectoryPickerState? = null,
    val workspaces: WorkspacesUiState = WorkspacesUiState(),
    val workspaceFiles: WorkspaceFilesUiState? = null,
    val workspaceExport: WorkspaceExportUiState? = null,
    val references: ReferencePickerState? = null,
    val referenceLabels: dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex =
        dev.fughetta.dshcompose.ui.composer.ReferenceLabelIndex.Empty,
    val usage: dev.fughetta.dshcompose.ui.transcript.UsageState =
        dev.fughetta.dshcompose.ui.transcript.UsageState(),
    val foldCompletedTurns: Boolean = true,
    val transcriptArtifact: dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactState =
        dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactState(),
    val sessionArtifacts: String? = null,
    val connectivity: dev.fughetta.dshcompose.ui.connectivity.ConnectivityState =
        dev.fughetta.dshcompose.ui.connectivity.ConnectivityState(),
    val backgroundNet: dev.fughetta.dshcompose.ui.runtime.BackgroundNetReason? = null,
    val engineControls: dev.fughetta.dshcompose.ui.runtime.EngineControlsState =
        dev.fughetta.dshcompose.ui.runtime.EngineControlsState(),
    val deepSeekAccount: dev.fughetta.dshcompose.ui.account.DeepSeekAccountState =
        dev.fughetta.dshcompose.ui.account.DeepSeekAccountState(),
    val appearance: dev.fughetta.dshcompose.ui.appearance.AppearanceState =
        dev.fughetta.dshcompose.ui.appearance.AppearanceState(),
    val appearanceLaunchFinished: Boolean = false,
    val openAppearanceRevision: Long = 0,
    val mediaGeneration: dev.fughetta.dshcompose.ui.media.MediaGenerationState =
        dev.fughetta.dshcompose.ui.media.MediaGenerationState(),
    val openAgentsRevision: Long = 0,
    val terminal: dev.fughetta.dshcompose.ui.terminal.TerminalState =
        dev.fughetta.dshcompose.ui.terminal.TerminalState(),
    val openTerminalRevision: Long = 0,
    val notificationSettings: dev.fughetta.dshcompose.ui.notify.NotificationSettingsState =
        dev.fughetta.dshcompose.ui.notify.NotificationSettingsState(),
    val notificationBanner: dev.fughetta.dshcompose.ui.notify.NotificationBanner? = null,
    val featurePage: String? = null,
    val featurePageRevision: Long = 0,
    val nativePlugins: dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState =
        dev.fughetta.dshcompose.ui.nativeplugins.NativePluginsState(),
    val pluginSeats: dev.fughetta.dshcompose.ui.engine_settings.PluginSeatsState =
        dev.fughetta.dshcompose.ui.engine_settings.PluginSeatsState(),
    val pluginSeatSession: String? = null,
    val openPluginSeatsRevision: Long = 0,
    val engineSettings: dev.fughetta.dshcompose.ui.engine_settings.EngineSettingsState =
        dev.fughetta.dshcompose.ui.engine_settings.EngineSettingsState(),
    val pluginManagement: dev.fughetta.dshcompose.ui.engine_settings.PluginManagementState =
        dev.fughetta.dshcompose.ui.engine_settings.PluginManagementState(),
    val openEngineSettingsRevision: Long = 0,
    val schedules: SchedulesUiState = SchedulesUiState(),
    val openSchedulesRevision: Long = 0,
    val performanceSettings: dev.fughetta.dshcompose.ui.phone.PerformanceSettingsState =
        dev.fughetta.dshcompose.ui.phone.PerformanceSettingsState(),
    val phoneControls: dev.fughetta.dshcompose.ui.phone.PhoneControlsUiState =
        dev.fughetta.dshcompose.ui.phone.PhoneControlsUiState(),
    val goal: GoalUiState? = null,
    val permissions: PermissionsUiState? = null,
    val sessionExport: SessionExportUiState? = null,
    val skills: SkillsUiState? = null,
    val jobs: JobsUiState? = null,
    val runtime: RuntimeUiState = RuntimeUiState(),
    val remoteHosts: List<dev.fughetta.dshcompose.domain.hosts.RemoteHost> = emptyList(),
    val remoteHostEditor: dev.fughetta.dshcompose.domain.hosts.RemoteHost? = null,
    val remoteHostsBusy: Boolean = true,
    val remoteHostError: String? = null,
    val hasVirtualizationFramework: Boolean = false,
    val hostIdentity: String = "local",
    /** The link owner's state for the selected host; the UI reads this instead of inferring. */
    val linkState: dev.fughetta.dshcompose.domain.hosts.LinkState =
        dev.fughetta.dshcompose.domain.hosts.LinkState.Offline,
    val theme: ThemeMode = ThemeMode.System,
    /** The active host's ui.json `theme.mode`, over [theme]. */
    val uiThemeMode: ThemeMode? = null,
    val dynamicColor: Boolean = true,

    /** 设置 › 语言: `zh`, `en`, or null to follow the system. */
    val language: String? = null,
    val shellPrefs: dev.fughetta.dshcompose.ui.shell.ShellPrefsState =
        dev.fughetta.dshcompose.ui.shell.ShellPrefsState(),
    val replyPrefs: dev.fughetta.dshcompose.ui.personal.ReplyPrefsState =
        dev.fughetta.dshcompose.ui.personal.ReplyPrefsState(),
    val error: String? = null,
) {
    /** Kept as a derived value so the UI has one source: the link owner's state. */
    val connectionLive: Boolean
        get() = linkState is dev.fughetta.dshcompose.domain.hosts.LinkState.Online
}

data class LicenseDocument(val path: String, val text: String)

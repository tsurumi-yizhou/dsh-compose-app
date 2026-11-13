package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.hosts.LinkState
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import dev.fughetta.dshcompose.domain.messages.UserQuestionRequest
import dev.fughetta.dshcompose.domain.permissions.PermissionSnapshot
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.references.FileReferenceCandidate
import dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.domain.skills.SkillInfo
import dev.fughetta.dshcompose.domain.workspaces.DirectoryEntry
import dev.fughetta.dshcompose.domain.workspaces.DirectoryLevel
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFileListing
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceTextPage
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.providers.*
import dev.fughetta.dshcompose.ui.schedules.*
import kotlinx.collections.immutable.*

data class SkillsUiState(
    val revision: Long = 0,
    val sessionId: String,
    val skills: PersistentList<dev.fughetta.dshcompose.domain.skills.SkillInfo> =
        persistentListOf(),
    val query: String = "",
    val selected: dev.fughetta.dshcompose.domain.skills.SkillInfo? = null,
    val loading: Boolean = true,
    val filtering: Boolean = false,
    val busy: Boolean = false,
    val canUseHere: Boolean = false,
    val creationUncertain: Boolean = false,
    val createdSessionId: String? = null,
    val error: String? = null,
)

enum class SessionExportOperation {
    Share,
    Save,
}

data class SessionArchiveShare(val path: String, val name: String)

data class SessionExportUiState(
    val revision: Long = 0,
    val sessionId: String,
    val id: String,
    val supported: Boolean,
    val name: String,
    val busy: Boolean = false,
    val operation: SessionExportOperation? = null,
    val bytes: Long = 0,
    val total: Long? = null,
    val needsPermission: Boolean = false,
    val share: SessionArchiveShare? = null,
    val savedTo: String? = null,
    val shared: Boolean = false,
    val error: String? = null,
)

enum class PermissionTarget {
    Current,
    Default,
}

data class PermissionChange(
    val value: String,
    val target: PermissionTarget,
    val revision: Long?,
    val remember: Boolean = true,
)

data class PermissionsUiState(
    val sessionId: String?,
    val snapshot: dev.fughetta.dshcompose.domain.permissions.PermissionSnapshot? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val stale: Boolean = false,
    val pending: PermissionChange? = null,
    val error: String? = null,
    val notice: String? = null,
)

data class DirectoryPickerState(
    val nonce: Long,
    val level: DirectoryLevel? = null,
    val path: String = "",
    val query: String = "",
    val showHidden: Boolean = false,
    val entries: PersistentList<DirectoryEntry> = persistentListOf(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val newFolder: String? = null,
    val error: String? = null,
    val registerWorkspace: Boolean = false,
    val recent: PersistentList<String> = persistentListOf(),
) {
    val canChoose
        get() =
            !loading &&
                !busy &&
                error == null &&
                newFolder == null &&
                level != null &&
                path == level.path

    val canCreateFolder
        get() =
            newFolder?.let {
                it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\u0000' !in it
            } == true
}

data class WorkspaceSection(val kind: String, val name: String?, val rows: List<Workspace>)

data class WorkspaceFilesUiState(
    val sessionId: String,
    val path: String = "",
    val listing: WorkspaceFileListing? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val preview: WorkspaceFilePreview? = null,
    val watchError: String? = null,
    val search: WorkspaceFileSearch = WorkspaceFileSearch(),
)

data class ReplyLinkUiState(
    val id: Long,
    val sessionId: String,
    val target: String,
    val external: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class WorkspaceFileSearch(
    val query: String = "",
    val results: PersistentList<FileReferenceCandidate> = persistentListOf(),
    val loading: Boolean = false,
    val error: String? = null,
)

enum class WorkspacePreviewKind {
    Text,
    Image,
    Pdf,
    Office,
}

data class WorkspaceFilePreview(
    val path: String,
    val page: WorkspaceTextPage? = null,
    val previous: PersistentList<Int> = persistentListOf(),
    val loading: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
    val watchError: String? = null,
    val kind: WorkspacePreviewKind = WorkspacePreviewKind.Text,
    val cachePath: String? = null,
    val fileVersion: String? = null,
    val missingFonts: PersistentList<String> = persistentListOf(),
)

data class WorkspaceExportUiState(
    val name: String,
    val busy: Boolean = true,
    val bytes: Long = 0,
    val total: Long? = null,
    val saved: Boolean = false,
    val error: String? = null,
)

enum class ImageExportOperation {
    Save,
    Share,
}

data class ImageShareFile(val path: String, val mime: String, val name: String)

data class ImageExportUiState(
    val id: String,
    val operation: ImageExportOperation,
    val name: String? = null,
    val busy: Boolean = true,
    val needsPermission: Boolean = false,
    val share: ImageShareFile? = null,
    val saved: Boolean = false,
    val shared: Boolean = false,
    val error: String? = null,
)

enum class ReferenceKind {
    Files,
    Conversations,
}

data class ReferencePickerState(
    val sessionId: String,
    val draft: String,
    val start: Int,
    val end: Int,
    val kind: ReferenceKind = ReferenceKind.Files,
    val query: String = "",
    val loading: Boolean = false,
    val files: PersistentList<FileReferenceCandidate> = persistentListOf(),
    val sessions: PersistentList<SessionReferenceCandidate> = persistentListOf(),
    val error: String? = null,
    val inline: Boolean = false,
    val pathOnly: Boolean = false,
    val quoted: Boolean = false,
)

fun workspaceSections(
    rows: List<Workspace>,
    preferences: WorkspacePreferences,
): List<WorkspaceSection> {
    val pinned = mutableListOf<Workspace>()
    val groups = linkedMapOf<String, MutableList<Workspace>>()
    val rest = mutableListOf<Workspace>()
    for (row in rows) when {
        row.id in preferences.pinned -> pinned.add(row)
        row.id in preferences.groups ->
            groups.getOrPut(preferences.groups.getValue(row.id)) { mutableListOf() }.add(row)
        else -> rest.add(row)
    }
    return buildList {
        if (pinned.isNotEmpty()) add(WorkspaceSection("pinned", null, pinned))
        groups.forEach { (name, items) -> add(WorkspaceSection("group", name, items)) }
        if (rest.isNotEmpty()) add(WorkspaceSection("rest", null, rest))
    }
}

enum class WorkspaceOperation {
    Rename,
    Remove,
    Group,
}

data class WorkspacePreferences(
    val selected: String? = null,
    val pinned: PersistentSet<String> = persistentSetOf(),
    val groups: PersistentMap<String, String> = persistentMapOf(),
    val recent: PersistentList<String> = persistentListOf(),
) {
    fun grouped(id: String, group: String): WorkspacePreferences =
        copy(
            groups = if (group.isBlank()) groups.removing(id) else groups.putting(id, group.trim()),
        )

    fun remembered(path: String): WorkspacePreferences =
        copy(recent = persistentListOf(path).addingAll(recent.filter { it != path }.take(3)))

    fun forgotten(id: String): WorkspacePreferences =
        copy(
            selected = selected.takeUnless { it == id },
            pinned = pinned.removing(id),
            groups = groups.removing(id),
        )
}

data class WorkspaceDialogState(
    val id: String,
    val operation: WorkspaceOperation,
    val title: String,
    val busy: Boolean = false,
    val error: String? = null,
)

data class WorkspaceSessionDialogState(
    val sessionId: String,
    val operation: WorkspaceSessionOperation,
    val stopActivity: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val openAfterRestore: Boolean = false,
)

data class WorkspacesUiState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val loading: Boolean = false,
    /**
     * [rows], [archived] and [pinned] come from a completed read of this host; a reload keeps them.
     */
    val loaded: Boolean = false,
    val rows: PersistentList<Workspace> = persistentListOf(),
    val selectedId: String? = null,
    val showArchived: Boolean = false,
    val archived: PersistentSet<String> = persistentSetOf(),
    val pinned: PersistentList<String> = persistentListOf(),
    val dialog: WorkspaceDialogState? = null,
    val error: String? = null,
    val writing: Boolean = false,
    val sessionDialog: WorkspaceSessionDialogState? = null,
    val preferences: WorkspacePreferences = WorkspacePreferences(),
    val preferencesLoaded: Boolean = false,
    val preferencesError: String? = null,
)

data class ComposerAttachment(
    val id: String,
    val name: String,
    val bytes: Long,
    val mediaType: String,
    val progress: Float? = null,
)

data class ApprovalUiState(
    val eventId: String,
    val toolName: String,
    val reason: String?,
    val displayReason: Map<String, String>,
    val connection: Long,
    val generation: Long,
    val responding: Boolean = false,
)

data class QuestionUiState(
    val request: UserQuestionRequest,
    val connection: Long,
    val generation: Long,
    val answers: Map<String, QuestionAnswer> = emptyMap(),
    val responding: Boolean = false,
    val remainingMs: Long? = null,
)

enum class RuntimePhase {
    NotInstalled,
    Installing,
    Installed,
    Starting,
    Running,
    Stopping,
    Error,
}

data class RuntimeUiState(
    val phase: RuntimePhase = RuntimePhase.NotInstalled,
    val progress: Float? = null,
    val error: String? = null,
    val logs: List<String> = emptyList(),
)

data class AppState(
    val contributions: FeatureContributions = FeatureContributions(),
    val featureCatalog: List<dev.fughetta.dshcompose.ui.shell.FeatureEntry> =
        dev.fughetta.dshcompose.ui.shell.FeatureCatalog,
    val composerActions: List<AppModuleContribution.ComposerAction> = builtInComposerActions,
    val messageRenderers: List<AppModuleContribution.MessageRenderer> = builtInMessageRenderers,
    val featurePages: List<AppModuleContribution.Page> = builtInPages,
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

package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.hosts.HostPairing
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.providers.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.sessions.SessionOperation
import kotlinx.collections.immutable.*

sealed interface AppAction {
    data class ShellPrefs(val action: dev.fughetta.dshcompose.ui.shell.ShellPrefsAction) : AppAction

    data class ReplyPrefs(val action: dev.fughetta.dshcompose.ui.personal.ReplyPrefsAction) :
        AppAction

    /** 问 AI: a new conversation's composer filled with [text], not sent. */
    data class AskAi(val text: String) : AppAction

    data class Usage(val action: dev.fughetta.dshcompose.ui.transcript.UsageAction) : AppAction

    data object TopUpBalance : AppAction

    data class PendingPrompts(
        val action: dev.fughetta.dshcompose.ui.messages.PendingPromptsAction,
    ) : AppAction

    data class RetryTranscriptNotice(val sessionId: String, val messageKey: String) : AppAction

    data class TranscriptArtifact(
        val sessionId: String,
        val file: dev.fughetta.dshcompose.domain.sessions.TranscriptArtifact,
        val action: dev.fughetta.dshcompose.ui.transcript.TranscriptArtifactAction,
    ) : AppAction

    data class OpenSessionArtifacts(val sessionId: String) : AppAction

    data object CloseSessionArtifacts : AppAction

    data object CloseTranscriptTable : AppAction

    data class TranscriptSheet(val sheet: Int) : AppAction

    data object RefreshTranscriptTable : AppAction

    data object OpenProviderSettings : AppAction

    data class FoldCompletedTurns(val value: Boolean) : AppAction

    data class EngineControls(
        val action: dev.fughetta.dshcompose.ui.runtime.EngineControlsAction,
    ) : AppAction

    data object OpenDeepSeekAccount : AppAction

    data class DeepSeekAccount(
        val action: dev.fughetta.dshcompose.ui.account.DeepSeekAccountAction,
    ) : AppAction

    data class Appearance(val action: dev.fughetta.dshcompose.ui.appearance.AppearanceAction) :
        AppAction

    data object OpenAppearance : AppAction

    data object FinishAppearanceLaunch : AppAction

    /** The chat's background-running line: tapped away, or [open] 后台保活. */
    data class BackgroundNet(val open: Boolean) : AppAction

    data class Media(val action: dev.fughetta.dshcompose.ui.media.MediaAction) : AppAction

    data class OpenMessageMenu(val sessionId: String, val messageKey: String) : AppAction

    data class MessageOperation(
        val operation: dev.fughetta.dshcompose.ui.messages.MessageOperation,
    ) : AppAction

    data object CloseMessageMenu : AppAction

    data class ClearQuote(val sessionId: String) : AppAction

    data class ReturnToOriginal(val sessionId: String) : AppAction

    data class OpenAgents(val sessionId: String) : AppAction

    data class Agents(val action: dev.fughetta.dshcompose.ui.agents.AgentsAction) : AppAction

    /** Makes "local" or a paired compose service's id the active host. */
    data object AddRemoteHost : AppAction

    data class RepairRemoteHost(val id: String) : AppAction

    data class RemoveRemoteHost(val id: String) : AppAction

    data class ForgetRemoteHost(val id: String) : AppAction

    data object CloseRemoteHost : AppAction

    class SaveRemoteHost(
        val name: String,
        val target: dev.fughetta.dshcompose.domain.hosts.HostPairing,
    ) : AppAction {
        override fun toString() = "SaveRemoteHost(<redacted>)"
    }

    data class SelectHost(val host: String) : AppAction

    data object OpenTerminal : AppAction

    data class Terminal(val action: dev.fughetta.dshcompose.ui.terminal.TerminalAction) : AppAction

    data class NotificationSettings(
        val action: dev.fughetta.dshcompose.ui.notify.NotificationSettingsAction,
    ) : AppAction

    data object OpenNotificationBanner : AppAction

    data object DismissNotificationBanner : AppAction

    data class VisibleSession(val sessionId: String, val visible: Boolean) : AppAction

    data class NativePluginVisibleTurns(val sessionId: String, val turns: List<String>) : AppAction

    data class NativePluginInvoke(
        val action: dev.fughetta.dshcompose.domain.nativeplugins.NativePluginAction,
        val scope: dev.fughetta.dshcompose.domain.nativeplugins.NativePluginScope,
        val message: String? = null,
    ) : AppAction

    data class NativePluginPanel(val pkg: String, val id: String) : AppAction

    data class NativePluginPanelAction(
        val action: dev.fughetta.dshcompose.ui.nativeplugins.NativePluginViewAction,
    ) : AppAction

    data class NativePluginConsent(val allowed: Boolean) : AppAction

    data class NativePluginSurface(val pkg: String, val surface: String, val enabled: Boolean) :
        AppAction

    data object CloseNativePluginPanel : AppAction

    data class OpenPluginSeats(val sessionId: String? = null) : AppAction

    data class PluginSeats(
        val action: dev.fughetta.dshcompose.ui.engine_settings.PluginSeatsAction,
    ) : AppAction

    data class EngineConfiguration(
        val action: dev.fughetta.dshcompose.ui.engine_settings.EngineSettingsAction,
    ) : AppAction

    data class PluginManagement(
        val action: dev.fughetta.dshcompose.ui.engine_settings.PluginManagementAction,
    ) : AppAction

    data class ConfigurePlugin(val entryId: String) : AppAction

    data object OpenSchedules : AppAction

    data class Schedules(val action: SchedulesAction) : AppAction

    data class PerformanceSettings(
        val action: dev.fughetta.dshcompose.ui.phone.PerformanceSettingsAction,
    ) : AppAction

    data class PhoneControls(
        val action: dev.fughetta.dshcompose.ui.phone.PhoneControlsAction,
    ) : AppAction

    data class ObserveGoal(val sessionId: String, val observing: Boolean) : AppAction

    data object ShowGoal : AppAction

    data object CloseGoal : AppAction

    data object RefreshGoal : AppAction

    data object EditGoal : AppAction

    data class ChangeGoalObjective(val text: String) : AppAction

    data object SaveGoal : AppAction

    data object CancelGoalEditor : AppAction

    data class ChangeGoal(val action: dev.fughetta.dshcompose.domain.goals.GoalAction) : AppAction

    data object AskClearGoal : AppAction

    data object ConfirmClearGoal : AppAction

    data object CancelClearGoal : AppAction

    data class OpenPermissions(val sessionId: String? = null) : AppAction

    data class SelectAgentPreset(val sessionId: String, val preset: String) : AppAction

    data object ClosePermissions : AppAction

    data class ObservePermissions(val observing: Boolean) : AppAction

    data object RefreshPermissions : AppAction

    data class SelectPermission(val value: String, val target: PermissionTarget) : AppAction

    data object ConfirmPermission : AppAction

    data object CancelPermission : AppAction

    data class RememberAutoReview(val remember: Boolean) : AppAction

    data class PickCommand(val sessionId: String, val name: String) : AppAction

    data object RefreshCommands : AppAction

    data class OpenSessionExport(val sessionId: String) : AppAction

    data class ExportSession(val operation: SessionExportOperation) : AppAction

    data object CloseSessionExport : AppAction

    data class OpenSkills(val sessionId: String) : AppAction

    data object CloseSkills : AppAction

    data class ObserveSkills(val observing: Boolean) : AppAction

    data object RefreshSkills : AppAction

    data class QuerySkills(val text: String) : AppAction

    data class ViewSkill(val name: String) : AppAction

    data object CloseSkillDetail : AppAction

    data class UseSkill(val fresh: Boolean) : AppAction

    data object OpenSkillCreatedSession : AppAction

    data class OpenJobs(val sessionId: String) : AppAction

    data object CloseJobs : AppAction

    data class ObserveJobs(val observing: Boolean) : AppAction

    data object ShareJobOutput : AppAction

    data object SaveJobOutput : AppAction

    data object RefreshJobs : AppAction

    data class OpenJobOutput(val id: String) : AppAction

    data object CloseJobOutput : AppAction

    data object RequestJobStop : AppAction

    data object CancelJobStop : AppAction

    data object ConfirmJobStop : AppAction

    data class ObserveAgentPresets(val observing: Boolean) : AppAction

    data object RefreshAgentPresets : AppAction

    data class SetDefaultAgentPreset(val id: String) : AppAction

    data class ViewAgentPreset(val id: String) : AppAction

    data object CloseAgentPresetDetail : AppAction

    data object RetryAgentPresetDocument : AppAction

    data class OpenPresetEditor(val mode: PresetEditMode, val id: String? = null) : AppAction

    data class EditPresetField(val field: PresetField, val value: String) : AppAction

    data class ChoosePresetBase(val id: String) : AppAction

    data class TogglePresetRow(val id: String, val enabled: Boolean) : AppAction

    data class ReplacePresetPrompt(val complete: Boolean) : AppAction

    data object RetryPresetBase : AppAction

    data object SubmitPreset : AppAction

    data object ClosePresetEditor : AppAction

    data object DiscardPresetEditor : AppAction

    data object KeepPresetEditor : AppAction

    data object OpenPresetCreatorSession : AppAction

    data class RemoveAgentPreset(val id: String) : AppAction

    data object ConfirmRemoveAgentPreset : AppAction

    data object CloseAgentPresetRemoval : AppAction

    data class ObserveSessionSearch(val observing: Boolean) : AppAction

    data class QuerySessions(
        val text: String,
        val composingStart: Int? = null,
        val composingEnd: Int? = null,
    ) : AppAction

    data object RetrySessionSearch : AppAction

    data object DismissSessionSearch : AppAction

    data class OpenSearchSession(val sessionId: String, val epoch: Long) : AppAction

    data object RefreshSessions : AppAction

    data class SelectSession(val id: String?) : AppAction

    data class Drawer(val action: DrawerAction) : AppAction

    data object NewSession : AppAction

    data class OpenDirectoryPicker(val sessionId: String? = null) : AppAction

    data class EditDirectoryPath(val value: String) : AppAction

    data class BrowseDirectory(val path: String? = null) : AppAction

    data class FilterDirectories(val query: String) : AppAction

    data class ShowHiddenDirectories(val show: Boolean) : AppAction

    data object ChooseDirectory : AppAction

    data object CloseDirectoryPicker : AppAction

    data object OpenCreateDirectory : AppAction

    data class EditNewDirectory(val name: String) : AppAction

    data object ConfirmCreateDirectory : AppAction

    data object CloseCreateDirectory : AppAction

    data object AddWorkspace : AppAction

    data class SelectWorkspace(val id: String?) : AppAction

    data class ShowArchivedSessions(val show: Boolean) : AppAction

    data class ManageWorkspace(val id: String, val operation: WorkspaceOperation) : AppAction

    data class EditWorkspaceTitle(val title: String) : AppAction

    data object ConfirmWorkspaceOperation : AppAction

    data object CloseWorkspaceDialog : AppAction

    data object ReloadWorkspaces : AppAction

    data class MoveWorkspace(val id: String, val beforeId: String?) : AppAction

    data class ManageWorkspaceSession(val id: String, val operation: WorkspaceSessionOperation) :
        AppAction

    data class ManageWorkspaceSessions(
        val ids: List<String>,
        val operation: WorkspaceSessionOperation,
    ) : AppAction

    data class StopActivityOnArchive(val stop: Boolean) : AppAction

    data object ConfirmWorkspaceSession : AppAction

    data object CloseWorkspaceSessionDialog : AppAction

    data class OpenWorkspaceFiles(val sessionId: String) : AppAction

    data class OpenReplyLink(val sessionId: String, val generation: Long, val target: String) :
        AppAction

    data class CloseReplyLink(val id: Long) : AppAction

    data class BrowseWorkspaceFiles(val path: String) : AppAction

    data object CloseWorkspaceFiles : AppAction

    data class PreviewWorkspaceFile(val path: String) : AppAction

    data class PageWorkspaceText(val next: Boolean) : AppAction

    data object ReloadWorkspaceText : AppAction

    data object CloseWorkspaceText : AppAction

    data class ObserveWorkspaceFiles(val observing: Boolean) : AppAction

    data class SearchWorkspaceFiles(val query: String) : AppAction

    data object RefreshWorkspaceFiles : AppAction

    data class ExportWorkspaceFile(val sessionId: String, val path: String) : AppAction

    data class ExportReplyImage(
        val request: dev.fughetta.dshcompose.ui.markdown.ReplyImageRequest,
        val operation: ImageExportOperation,
    ) : AppAction

    data object CloseImageExport : AppAction

    data object OpenImageSettings : AppAction

    data object CloseWorkspaceExport : AppAction

    data class OpenReferences(val sessionId: String, val start: Int, val end: Int) : AppAction

    data class ReferenceQuery(val query: String) : AppAction

    data class ReferenceType(val kind: ReferenceKind) : AppAction

    data class InsertFileReference(val path: String) : AppAction

    data class InsertSessionReference(val sessionId: String) : AppAction

    data object CloseReferences : AppAction

    data class PinWorkspaceLocally(val id: String, val pinned: Boolean) : AppAction

    data class OpenSession(val sessionId: String) : AppAction

    data class OpenModelPicker(val sessionId: String) : AppAction

    data class ChooseModel(val selection: ModelSelection) : AppAction

    data object ConfirmModel : AppAction

    data object CloseModelPicker : AppAction

    data object ReloadModels : AppAction

    data class ObserveProviders(val observing: Boolean) : AppAction

    data object RefreshProviders : AppAction

    data class ObserveSubscriptions(val observing: Boolean) : AppAction

    data object RefreshSubscriptions : AppAction

    data class ObserveModelDefaults(val observing: Boolean) : AppAction

    data object RefreshModelDefaults : AppAction

    data object OpenDefaultModelPicker : AppAction

    data class ChooseDefaultModel(val selection: ModelSelection) : AppAction

    data object ConfirmDefaultModel : AppAction

    data object CloseDefaultModelPicker : AppAction

    data class ChangeDefaultModelMode(val remember: Boolean) : AppAction

    data class BeginSubscriptionLogin(val subscription: Subscription) : AppAction

    data object RetrySubscriptionLogin : AppAction

    class AnswerSubscriptionLogin(val answer: ProviderKey) : AppAction

    data class CloseSubscriptionLogin(val nonce: Long) : AppAction

    data class SubscriptionBrowserOpened(val nonce: Long) : AppAction

    data class RequestSubscriptionSignOut(val subscription: Subscription) : AppAction

    data object ConfirmSubscriptionSignOut : AppAction

    data object DismissSubscriptionSignOut : AppAction

    data class ManageProviderKey(val account: ProviderAccount, val removing: Boolean = false) :
        AppAction

    class SubmitProviderKey(val key: ProviderKey) : AppAction

    data object ConfirmRemoveProviderKey : AppAction

    data object CloseProviderKeyDialog : AppAction

    data object OpenMagpieProvider : AppAction

    data object OpenDeepSeekProvider : AppAction

    class SaveProviderEndpoint(val key: ProviderKey?) : AppAction

    data class OpenProviderEditor(val route: String? = null, val removing: Boolean = false) :
        AppAction

    data class EditProviderField(val field: ProviderEditorField, val value: String) : AppAction

    data class ChangeProviderApi(val api: ProviderApi) : AppAction

    data object SaveProviderProfile : AppAction

    data object CloseProviderEditor : AppAction

    data object DiscardProviderEditsAndRefresh : AppAction

    data object OpenProviderDiscovery : AppAction

    class SubmitProviderDiscovery(val key: ProviderKey?) : AppAction

    data object CloseProviderDiscovery : AppAction

    data class FilterDiscoveredModels(val query: String) : AppAction

    data class ToggleDiscoveredModel(val id: String) : AppAction

    data object SelectAllDiscoveredModels : AppAction

    data object ImportDiscoveredModels : AppAction

    data class OpenModelParameters(val id: String) : AppAction

    data object ReloadProviderCatalog : AppAction

    data class OpenCatalogContext(val id: String, val name: String) : AppAction

    data class EditCatalogContext(val value: String) : AppAction

    data object ApplyCatalogContext : AppAction

    data object CloseCatalogContext : AppAction

    data class EditModelParameter(val field: ModelParameterField, val value: String) : AppAction

    data class ChangeModelImages(val enabled: Boolean) : AppAction

    data class ChangeModelThinking(val enabled: Boolean) : AppAction

    data class ToggleModelEffort(val effort: String) : AppAction

    data object ApplyModelParameters : AppAction

    data object CloseModelParameters : AppAction

    data class ManageSession(val sessionId: String, val operation: SessionOperation) : AppAction

    data class EditSessionTitle(val title: String) : AppAction

    data object DismissSessionDialog : AppAction

    data object ConfirmSessionOperation : AppAction

    data class LoadHistory(val sessionId: String) : AppAction

    data class ChangeDraft(
        val sessionId: String,
        val text: String,
        val spans: PersistentList<ReferenceSpan>,
        val selectionStart: Int? = null,
        val selectionEnd: Int? = null,
        val composing: Boolean = false,
    ) : AppAction

    data class Send(val sessionId: String) : AppAction

    sealed interface ViewRequest : AppAction {
        val sessionId: String
        val messageKey: String
        val generation: Long
        val source: String
    }

    data class ReplyToView(
        override val sessionId: String,
        override val messageKey: String,
        override val generation: Long,
        override val source: String,
        val path: String,
        val selected: List<Int> = emptyList(),
        val freetext: String? = null,
        val confirmed: Boolean? = null,
    ) : ViewRequest

    data class AskViewResend(
        override val sessionId: String,
        override val messageKey: String,
        override val generation: Long,
        override val source: String,
    ) : ViewRequest

    data class GenuiAction(
        override val sessionId: String,
        override val messageKey: String,
        override val generation: Long,
        override val source: String,
        val input: dev.fughetta.dshcompose.ui.dynamicui.GenuiInput,
    ) : ViewRequest

    data class PickAttachments(
        val sessionId: String,
        val kind: ComposerAttachmentKind = ComposerAttachmentKind.Files,
    ) : AppAction

    data class CapturePhoto(val sessionId: String) : AppAction

    data class InsertLocation(val sessionId: String) : AppAction

    data class RemoveAttachment(val sessionId: String, val attachmentId: String) : AppAction

    data class OpenImage(val sessionId: String, val attachment: ChatAttachment) : AppAction

    data object CloseImage : AppAction

    data object LoadLicenses : AppAction

    data class RespondApproval(
        val sessionId: String,
        val approval: ApprovalUiState,
        val allow: Boolean,
    ) : AppAction

    data class EditQuestion(
        val sessionId: String,
        val request: QuestionUiState,
        val answer: QuestionAnswer,
    ) : AppAction

    data class RespondQuestion(
        val sessionId: String,
        val request: QuestionUiState,
        val reject: Boolean = false,
    ) : AppAction

    data class StopTurn(val sessionId: String) : AppAction

    data class ChangeLanguage(val language: String?) : AppAction

    data object InstallRuntime : AppAction

    data object StartRuntime : AppAction

    data object StopRuntime : AppAction

    data class ChangeTheme(val theme: ThemeMode) : AppAction

    data class ChangeDynamicColor(val enabled: Boolean) : AppAction
}

enum class ComposerAttachmentKind {
    Photos,
    Files,
    Media,
}

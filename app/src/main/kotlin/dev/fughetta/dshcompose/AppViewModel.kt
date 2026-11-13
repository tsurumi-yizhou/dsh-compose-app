package dev.fughetta.dshcompose

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.appearance.handleAppearance
import dev.fughetta.dshcompose.attachments.importShares
import dev.fughetta.dshcompose.connection.closeConnectionScreens
import dev.fughetta.dshcompose.connection.handleHostActions
import dev.fughetta.dshcompose.connection.status
import dev.fughetta.dshcompose.di.createAppearanceController
import dev.fughetta.dshcompose.di.createBackgroundNet
import dev.fughetta.dshcompose.di.createComposerFiles
import dev.fughetta.dshcompose.di.createDeepSeekAccount
import dev.fughetta.dshcompose.di.createDirectoryPicker
import dev.fughetta.dshcompose.di.createEngineControls
import dev.fughetta.dshcompose.di.createEngineSettings
import dev.fughetta.dshcompose.di.createImageExports
import dev.fughetta.dshcompose.di.createLocalSessionObserver
import dev.fughetta.dshcompose.di.createMediaGeneration
import dev.fughetta.dshcompose.di.createNativeImageExports
import dev.fughetta.dshcompose.di.createNativePlugins
import dev.fughetta.dshcompose.di.createNotificationSettings
import dev.fughetta.dshcompose.di.createNotifications
import dev.fughetta.dshcompose.di.createPendingPrompts
import dev.fughetta.dshcompose.di.createPerformanceMonitor
import dev.fughetta.dshcompose.di.createPermissions
import dev.fughetta.dshcompose.di.createPhoneTurnEnd
import dev.fughetta.dshcompose.di.createPhoneTurnResults
import dev.fughetta.dshcompose.di.createPluginManagement
import dev.fughetta.dshcompose.di.createReferences
import dev.fughetta.dshcompose.di.createRemoteHostsRepository
import dev.fughetta.dshcompose.di.createReplyImageDownloads
import dev.fughetta.dshcompose.di.createReplyImageLoader
import dev.fughetta.dshcompose.di.createReplyLinks
import dev.fughetta.dshcompose.di.createRuntime
import dev.fughetta.dshcompose.di.createSelectedConnection
import dev.fughetta.dshcompose.di.createSessionArchives
import dev.fughetta.dshcompose.di.createSessionExport
import dev.fughetta.dshcompose.di.createSkills
import dev.fughetta.dshcompose.di.createTerminal
import dev.fughetta.dshcompose.di.createTranscriptArtifacts
import dev.fughetta.dshcompose.di.createUiConfig
import dev.fughetta.dshcompose.di.createUsage
import dev.fughetta.dshcompose.di.createWorkspaceExporter
import dev.fughetta.dshcompose.di.createWorkspaceFiles
import dev.fughetta.dshcompose.di.createWorkspaces
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.hosts.LocalHostObservation
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import dev.fughetta.dshcompose.engine_settings.handleEnginePlugins
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.messages.refreshPendingPrompts
import dev.fughetta.dshcompose.nativeplugins.handleNativePlugins
import dev.fughetta.dshcompose.notify.Notifications
import dev.fughetta.dshcompose.notify.handleNotifications
import dev.fughetta.dshcompose.phoneui.handlePhoneControls
import dev.fughetta.dshcompose.providers.handleProvidersModelsSearch
import dev.fughetta.dshcompose.sessions.applyModel
import dev.fughetta.dshcompose.sessions.approvalsFor
import dev.fughetta.dshcompose.sessions.bindConversation
import dev.fughetta.dshcompose.sessions.cleanupDeletedSession
import dev.fughetta.dshcompose.sessions.handleAgentsGoals
import dev.fughetta.dshcompose.sessions.handlePermissionsCommandsSkillsJobs
import dev.fughetta.dshcompose.sessions.prepareConversationDraft
import dev.fughetta.dshcompose.sessions.questionsFor
import dev.fughetta.dshcompose.share.Shares
import dev.fughetta.dshcompose.shortcuts.Shortcuts
import dev.fughetta.dshcompose.transcript.handleTranscriptMedia
import dev.fughetta.dshcompose.transcript.handleUsageAccount
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.conversation.ChatUiState
import dev.fughetta.dshcompose.ui.messages.*
import dev.fughetta.dshcompose.workspaces.handleWorkspacesFiles
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** Retains connection ownership across Activity recreation; credentials remain in memory. */
class AppViewModel(internal val app: Application) : AndroidViewModel(app) {
    @PublishedApi internal val childModels = ViewModelStore()

    internal inline fun <reified T : ViewModel> ownedModel(noinline create: () -> T): T =
        ViewModelProvider(childModels, viewModelFactory { initializer { create() } })[T::class.java]

    private val actionHandlers: List<(AppAction) -> Boolean> =
        listOf(
            ::handleUsageAccount,
            ::handleProvidersModelsSearch,
            ::handleEnginePlugins,
            ::handleAppearance,
            ::handlePhoneControls,
            ::handleAgentsGoals,
            ::handleHostActions,
            ::handleNotifications,
            ::handleNativePlugins,
            ::handlePermissionsCommandsSkillsJobs,
            ::handleWorkspacesFiles,
            ::handleTranscriptMedia,
        )
    internal val settings = (app as ClientApplication).settingsRepository
    internal var settingsErrorRevision = 0L
    val drawerUi =
        dev.fughetta.dshcompose.ui.sessions.SessionsDrawerState(
            (app as ClientApplication).settingsRepository,
            viewModelScope,
            (app as ClientApplication).sessionsRepository.state,
        )
    val sessionsModel by lazy {
        ownedModel {
            dev.fughetta.dshcompose.ui.sessions.SessionsViewModel(
                (app as ClientApplication).sessionsRepository,
                offlineRename = { sessionId, title ->
                    runCatching { sessionRepository.renameOffline(sessionId, title) }.getOrNull()
                },
            )
        }
    }
    internal var attentionTimer: Job? = null
    internal val pendingSince = mutableMapOf<String, Long>()
    internal var attentionSessions: List<SessionSummary> = emptyList()
    internal var draftCreating = false
    internal val replyPreferences by lazy {
        ownedModel { dev.fughetta.dshcompose.personal.ReplyPrefsViewModel(app) }
    }
    internal val launcherIconModel by lazy {
        ownedModel { dev.fughetta.dshcompose.icons.LauncherIconViewModel(app) }
    }
    internal var shellPreferencesErrorRevision = 0L
    internal val featureModules =
        dev.fughetta.dshcompose.domain.features
            .ModuleRegistry<AppModuleContribution>(viewModelScope)
            .apply { publish("builtin", listOf(builtInModule())) }
    internal val mutableState =
        kotlinx.coroutines.flow.MutableStateFlow(
            AppState(
                contributions =
                    FeatureContributions(
                        featureModules.state.value.contributions
                            .filterIsInstance<AppModuleContribution.Menu>()
                            .map { it.entry }
                            .toPersistentList(),
                    ),
                language = dev.fughetta.dshcompose.i18n.AppStrings.language(app),
                // The host restored below, so the drawer names it before its link is up.
                hostIdentity =
                    app.localPreferences("host_selection").getString("selected", "local")
                        ?: "local",
            ),
        )
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    internal val runtime = createRuntime()
    internal val icons = launcherIconModel
    val appearanceController = createAppearanceController()
    internal val shortcuts =
        Shortcuts(app) { event ->
            viewModelScope.launch {
                val id = event["sessionId"] as? String
                if (id != null) mutableState.update { it.copy(openSessionId = id) }
                else onAction(AppAction.NewSession)
            }
        }
    internal val notifications: Notifications = createNotifications()
    internal val composerFiles = createComposerFiles()
    internal val pendingPrompts = createPendingPrompts()
    internal val submitPendingMessage = SubmitPendingPromptUseCase(pendingPrompts)
    internal val pendingOperations = mutableMapOf<PendingPromptsOwner, String>()
    internal val composerAttachments = ComposerAttachmentsState()
    internal val imports = Mutex()
    internal var pickerSession: Pair<String, Long>? = null
    internal val retiredSessions = mutableSetOf<String>()
    internal val sharedInbox = ArrayDeque<Map<String, Any?>>()
    internal var importingShares: Job? = null
    internal val shares =
        Shares(app) { event ->
            sharedInbox.addLast(event)
            importShares()
        }
    internal val intentRouter =
        dev.fughetta.dshcompose.links.IntentRouter(
            openPage = ::openPageLink,
            onUnavailablePage = {
                mutableState.update {
                    it.copy(
                        error =
                            getApplication<Application>()
                                .getString(UiR.string.markdown_link_unavailable),
                    )
                }
            },
            selectSession = { onAction(AppAction.SelectSession(it)) },
            newSession = { onAction(AppAction.NewSession) },
            newConversationDraft = ::prepareConversationDraft,
            onNotification = { notifications.handleIntent(it) },
            onShortcut = { shortcuts.handleIntent(it) },
            onShare = { shares.handleIntent(it) },
        )
    internal val client: ConnectedHost?
        get() = selectedConnection.session

    @Volatile internal var connectionNumber = 0L
    val codeHighlighter = dev.fughetta.dshcompose.ui.markdown.CodeHighlighter()
    internal val replyImageDownloads = createReplyImageDownloads()
    val replyImageLoader = createReplyImageLoader()
    internal val nativeImageExports = createNativeImageExports()
    internal val imageExports = createImageExports()
    internal val requestResponses = mutableSetOf<String>()
    internal var requestsMonitor: Job? = null
    internal var eventsMonitor: Job? = null
    internal val questionDrafts = mutableMapOf<String, Map<String, QuestionAnswer>>()
    internal var questionWaitOwner: SessionsRepository? = null
    internal val questionWaits = mutableMapOf<String, Job>()
    internal val questionRemaining = mutableMapOf<String, Long>()
    internal var connectionIdentity: Pair<String, String>? = null
    internal val hostSelection = app.localPreferences("host_selection")
    internal var selectedHost =
        hostSelection.getString("selected", "local")?.takeIf {
            it == "local" || it.startsWith("compose-")
        } ?: "local"

    /**
     * Engine home the host answered with last time; offline prompts need it before a link exists.
     */
    internal fun rememberHome(host: String, home: String) {
        if (home.isEmpty()) return
        hostSelection.edit().putString("home:$host", home).apply()
    }

    internal fun lastHome(host: String): String? = hostSelection.getString("home:$host", null)

    internal val hosts: dev.fughetta.dshcompose.domain.hosts.HostsRepository
        get() = (app as ClientApplication).hostsRepository

    internal val selectedConnection by lazy { createSelectedConnection() }
    internal val connectionSession
        get() = selectedConnection.session

    internal var hostWorkingDirectory: String? = null
    internal val remoteHostsRepository = createRemoteHostsRepository()

    internal val sendOwners = mutableMapOf<String, Any>()
    internal var connecting: Job? = null
    internal var connectionMonitor: Job? = null
    internal var watchedId: String? = null
    val conversationModel by lazy {
        ownedModel {
            dev.fughetta.dshcompose.ui.conversation.ConversationViewModel(conversations) {
                id,
                publication,
                previous ->
                val view = publication.view
                goalsRepository.transcript(id, view.goal, view.running, !view.syncing)
                if (publication.permissionChanged) permissions.sessionChanged(id)
                if (publication.rememberSelection) settings.rememberSession(selectedHost, id)
                if (
                    previous?.inbox !== view.inbox ||
                        previous.pendingPrompts.owner?.home != publication.home
                )
                    refreshPendingPrompts(id)
            }
        }
    }
    internal val activeConversation: dev.fughetta.dshcompose.domain.sessions.ConversationRepository?
        get() = conversationModel.current

    internal val sessionRepository = (app as ClientApplication).sessionsRepository
    val transcriptArtifacts = createTranscriptArtifacts()
    internal val backgroundNet = createBackgroundNet()
    internal val deepSeekAccount = createDeepSeekAccount()
    internal val usage = createUsage()
    internal val mediaRepository: dev.fughetta.dshcompose.domain.media.MediaRepository
        get() = (app as ClientApplication).mediaRepository

    internal val mediaGeneration = createMediaGeneration()
    internal val terminal = createTerminal()
    internal val nativePlugins = createNativePlugins()

    internal fun pluginLocale() =
        dev.fughetta.dshcompose.i18n.AppStrings.context(getApplication<Application>())
            .resources
            .configuration
            .locales[0]
            .toLanguageTag()

    internal val pluginSeats by lazy {
        ownedModel { dev.fughetta.dshcompose.engine_settings.PluginSeatsViewModel() }
    }
    internal val engineSettings = createEngineSettings()
    internal val pluginManagement = createPluginManagement()
    internal val schedulesRepository = (app as ClientApplication).schedulesRepository
    internal val runtimeRestart = Mutex()
    internal val phoneControls by lazy {
        ownedModel {
            dev.fughetta.dshcompose.phoneui.PhoneControlsViewModel(
                app,
                localRuntime = {
                    dev.fughetta.dshcompose.phoneui.PhoneLocalRuntime(
                        runtime.phase == RuntimeController.Phase.RUNNING,
                    )
                },
                restartLocalEngine = { restartForPhonePreferences() },
            )
        }
    }
    val engineControls = createEngineControls()
    internal var pendingComposeText: String? = null
    internal val performanceMonitor = createPerformanceMonitor()
    internal val performanceSettings by lazy {
        ownedModel {
            dev.fughetta.dshcompose.phoneui.PerformanceSettingsViewModel(
                app,
                performanceMonitor,
            )
        }
    }
    val performanceState
        get() = performanceSettings.state

    internal var pendingLocalReveal: String? = null
    internal val localObserver: LocalHostObservation = createLocalSessionObserver()
    internal val phoneTurnEnd = createPhoneTurnEnd()
    internal val phoneTurnResults = createPhoneTurnResults()
    internal val notificationPreferences:
        dev.fughetta.dshcompose.notify.NotificationSettingsViewModel by lazy {
        ownedModel {
            dev.fughetta.dshcompose.notify.NotificationSettingsViewModel(app) {
                notificationSettings.settingsEffect(it)
            }
        }
    }
    internal val notificationSettings = createNotificationSettings()
    internal var visibleSessionId: String? = null
    internal var foreground = false
    internal var pendingNotification: dev.fughetta.dshcompose.ui.notify.NotificationTarget? = null
    internal val goalsRepository = (app as ClientApplication).goalsRepository
    internal val permissions = createPermissions()

    internal fun appCommandAction(name: String, sessionId: String): AppAction? =
        featureModules.state.value.contributions
            .filterIsInstance<AppModuleContribution.Command>()
            .firstOrNull { it.name == name }
            ?.action
            ?.invoke(sessionId)

    internal val commands =
        dev.fughetta.dshcompose.ui.commands.CommandsState(
            viewModelScope,
            appCommands = {
                featureModules.state.value.contributions
                    .filterIsInstance<AppModuleContribution.Command>()
                    .map { command -> command.describe { AppStrings.get(app, it) } }
            },
            decorate = { command ->
                dev.fughetta.dshcompose.ui.commands.nativeCommandCopy(command) {
                    AppStrings.get(app, it)
                }
            },
            failed = { AppStrings.get(app, UiR.string.commands_unavailable) },
        )
    val commandPalette = commands.state
    internal val sessionArchives = createSessionArchives()
    internal val sessionExport = createSessionExport()
    internal val composer =
        ComposerState((app as ClientApplication).draftsRepository, viewModelScope)
    internal val conversations =
        dev.fughetta.dshcompose.ui.conversation.ConversationsState(
            composer,
            composerAttachments,
        )
    val conversationState = conversations.state
    internal val uiConfig = createUiConfig()
    internal val skills = createSkills()
    internal val jobsRepository = (app as ClientApplication).jobsRepository.connection

    val modelsModel by lazy {
        ownedModel {
            dev.fughetta.dshcompose.ui.models.ModelsViewModel(
                repository = (app as ClientApplication).sessionModelsRepository,
                onSelected = { id, operations, result ->
                    if ((app as ClientApplication).sessionModelsRepository.value === operations)
                        client?.let { owner -> applyModel(id, owner, result) }
                },
                onDraftSelected = { selection ->
                    updateChat(DRAFT_SESSION_ID) { it.copy(model = selection) }
                },
            )
        }
    }

    internal val pickedReferenceLabels = MutableStateFlow(persistentSetOf<String>())
    internal val references = createReferences()
    internal val workspaceExporter = createWorkspaceExporter()
    internal val workspaceFiles = createWorkspaceFiles()
    internal val replyLinks = createReplyLinks()
    internal val workspaces = createWorkspaces()
    internal val directoryPicker = createDirectoryPicker()
    internal val sink = RuntimeController.EventSink { event ->
        viewModelScope.launch {
            when (event["type"]) {
                "status" ->
                    @Suppress("UNCHECKED_CAST")
                    status(event["status"] as? Map<String, Any?> ?: return@launch)
                "logs" -> {
                    val entries =
                        (event["entries"] as? List<*>).orEmpty().mapNotNull {
                            (it as? Map<*, *>)?.get("line") as? String
                        }
                    mutableState.update {
                        it.copy(
                            runtime =
                                it.runtime.copy(logs = (it.runtime.logs + entries).takeLast(300)),
                        )
                    }
                }
                "progress" -> {
                    val done = (event["done"] as? Number)?.toFloat() ?: 0f
                    val total = (event["total"] as? Number)?.toFloat() ?: 0f
                    mutableState.update {
                        it.copy(
                            runtime =
                                it.runtime.copy(progress = if (total > 0) done / total else null),
                        )
                    }
                }
            }
        }
    }

    init {
        bindState()
    }

    internal fun <T> collectInto(
        flow: kotlinx.coroutines.flow.Flow<T>,
        reduce: (AppState, T) -> AppState,
    ) = mutableState.bind(viewModelScope, flow, reduce)

    fun onAction(action: AppAction) {
        if (action is AppAction.ReplyPrefs) {
            replyPreferences.action(action.action)
            return
        }
        for (handle in actionHandlers) if (handle(action)) return
    }

    internal fun mutateDrawer(transform: (DrawerView) -> DrawerView) = drawerUi.mutate(transform)

    internal fun confirmDrawerDeletion() {
        if (sessionsModel.dialogState.value?.busy != true)
            sessionsModel.confirmDeletion { id -> confirmSessionDeleted(id) }
    }

    internal suspend fun confirmSessionDeleted(id: String) {
        drawerUi.removeDeleted(setOf(id))
        cleanupDeletedSession(id)
    }

    internal data class MediaPublication(
        val host: String,
        val live: Boolean,
        val session: String?,
        val revision: Long,
        val entries: Map<String, MediaTranscriptItem>,
    )

    fun mediaStoragePermission(request: (suspend () -> Boolean)?) =
        mediaGeneration.attachStoragePermission(request)

    fun attachNativePlugins(
        activity: androidx.activity.ComponentActivity?,
        launcher: ((Intent) -> Unit)? = null,
    ) = nativePlugins.attach(activity, launcher)

    fun nativePluginFileResult(result: androidx.activity.result.ActivityResult) =
        nativePlugins.acceptFileResult(result)

    // Wi-Fi ↔ cellular, VPN up or down: sockets bound to the old network are dead, so remote links
    // check again at once instead of waiting out their timers. The first callback is the network
    // already in use.
    internal val networkWatch =
        object : android.net.ConnectivityManager.NetworkCallback() {
            internal var current: android.net.Network? = null
            internal var started = false

            override fun onAvailable(network: android.net.Network) {
                val changed = started && network != current
                started = true
                current = network
                if (changed) viewModelScope.launch { uiConfig.kick() }
            }

            override fun onLost(network: android.net.Network) {
                if (network == current) current = null
            }
        }

    init {
        observeNetwork()
    }

    fun claimNotificationPermission(id: String) = notificationSettings.claimPermission(id)

    suspend fun nativePluginAsset(pkg: String, path: String) = nativePlugins.asset(pkg, path)

    fun islandExternalFailed() {
        mutableState.update {
            it.copy(
                error = getApplication<Application>().getString(UiR.string.markdown_link_failed),
            )
        }
    }

    internal fun refreshUsageAccount() {
        val local = localObserver.lease
        usage.monitor.account(
            state.value.deepSeekAccount.balance.takeIf {
                selectedHost == "local" &&
                    local != null &&
                    deepSeekAccount.identityFor(local.generation)
            },
        )
    }

    fun previewTranscriptFile(sessionId: String, path: String) {
        workspaceFiles.open(sessionId)
        workspaceFiles.preview(path)
    }

    internal fun openFeature(page: String) = mutableState.update {
        it.copy(featurePage = page, featurePageRevision = it.featurePageRevision + 1)
    }

    /** A page link (`dsh://open/<id>`) opens its page and nothing else; false for unknown ids. */
    internal fun openPageLink(id: String): Boolean {
        when {
            opensFeaturePage(id) -> openFeature(id)
            else -> return false
        }
        return true
    }

    fun claimPhonePermission(id: String) = phoneControls.claimPermission(id)

    fun claimArchivePermission(id: String) = sessionExport.claimPermission(id)

    fun archivePermissionResult(granted: Boolean) = sessionExport.permissionResult(granted)

    fun claimArchiveShare(id: String) = sessionExport.claimShare(id)

    fun archiveShared(id: String, launched: Boolean) = sessionExport.shared(id, launched)

    fun claimImagePermission(id: String): Boolean = imageExports.claimPermission(id)

    fun imagePermissionResult(granted: Boolean) = imageExports.permissionResult(granted)

    fun claimImageShare(id: String): Boolean = imageExports.claimShare(id)

    fun imageShared(id: String, launched: Boolean) = imageExports.shared(id, launched)

    fun prepareWorkspaceExport(sessionId: String, path: String): String? =
        workspaceExporter.prepare(sessionId, path)

    fun workspaceExportDestination(uri: Uri?) = workspaceExporter.picked(uri)

    fun workspaceExportPickerFailed() = workspaceExporter.pickerFailed()

    fun prepareFileSelection(sessionId: String): Boolean {
        if (conversations.state.value.chats[sessionId]?.readOnly == true) return false
        if (
            sessionId in retiredSessions ||
                conversations.state.value.chats[sessionId]?.sending == true
        )
            return false
        pickerSession = sessionId to connectionNumber
        return true
    }

    internal data class IncomingAttachment(
        val uri: Uri,
        val name: String? = null,
        val mediaType: String? = null,
    )

    internal fun openSession(id: String) = bindConversation(id)

    internal fun updateChat(id: String, transform: (ChatUiState) -> ChatUiState) {
        if (id !in retiredSessions) conversations.update(id, transform)
    }

    internal fun updateComposer(
        id: String,
        preserveReferenceQuery: Boolean = false,
        replaceText: Boolean = false,
        transform: (ComposerInput) -> ComposerInput,
    ) {
        if (id in retiredSessions) return
        val input = composer.edit(id, replaceText, transform)
        updateChat(id) { it }
        if (!preserveReferenceQuery) references.externalDraft(id, input.draft)
    }

    internal fun refreshChatRequests(id: String) =
        updateChat(id) { it.copy(approvals = approvalsFor(id), questions = questionsFor(id)) }

    internal fun performSessionOperation() =
        sessionsModel.confirm(
            renamed = { id, title -> updateChat(id) { it.copy(title = title) } },
            forked = { id ->
                mutableState.update { it.copy(openSessionId = id) }
                openSession(id)
            },
            deleted = { id -> confirmSessionDeleted(id) },
        )

    internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    fun handleIntent(intent: Intent) {
        intentRouter.handle(intent)
    }

    override fun onCleared() {
        childModels.clear()
        runCatching {
            getApplication<Application>()
                .getSystemService(android.net.ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkWatch)
        }
        runtime.removeSink(sink)
        codeHighlighter.close()
        imageExports.close()
        replyImageLoader.shutdown()
        replyImageDownloads.close()

        closeConnectionScreens()
        featureModules.close()
        notificationSettings.close()
        phoneTurnResults.close()
        phoneTurnEnd.close()
        hosts.releaseLocalObserver(localObserver)
        performanceMonitor.close()
        deepSeekAccount.close()
        connecting?.cancel()
        connectionMonitor?.cancel()
        requestsMonitor?.cancel()
        eventsMonitor?.cancel()
        questionWaits.values.forEach { it.cancel() }
        hosts.releaseSelection(selectedConnection)
        pendingPrompts.close()
        pendingOperations.clear()
        engineControls.close()
        appearanceController.close()
        notifications.close()
        shares.close()
        composerFiles.close()
        super.onCleared()
    }
}

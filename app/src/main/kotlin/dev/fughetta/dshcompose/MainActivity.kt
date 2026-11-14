package dev.fughetta.dshcompose

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.attachments.importPickedFiles
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.launch.LaunchThemeSync
import dev.fughetta.dshcompose.runtime.LaunchStart
import dev.fughetta.dshcompose.sessions.foreground
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.AppShell
import dev.fughetta.dshcompose.ui.goals.GoalsViewModel
import dev.fughetta.dshcompose.ui.jobs.JobsViewModel
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.settings.SettingsViewModel
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()
    private val sessionsModel
        get() = model.sessionsModel

    private val searchModel:
        dev.fughetta.dshcompose.ui.search.SessionSearchViewModel by viewModels {
        viewModelFactory {
            initializer {
                dev.fughetta.dshcompose.ui.search.SessionSearchViewModel(
                    (application as ClientApplication).sessionsRepository,
                )
            }
        }
    }
    private val saveJobOutput =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            jobsModel.saveDocument(uri?.toString())
        }
    private val jobsModel: JobsViewModel by viewModels {
        viewModelFactory {
            initializer {
                JobsViewModel(
                    (application as ClientApplication).jobsRepository.connection,
                    (application as ClientApplication).jobsRepository,
                )
            }
        }
    }
    private val goalsModel: GoalsViewModel by viewModels {
        viewModelFactory {
            initializer { GoalsViewModel((application as ClientApplication).goalsRepository) }
        }
    }
    private val schedulesModel: SchedulesViewModel by viewModels {
        viewModelFactory {
            initializer {
                SchedulesViewModel((application as ClientApplication).schedulesRepository)
            }
        }
    }
    private val shellPreferencesModel:
        dev.fughetta.dshcompose.ui.shell.ShellPrefsViewModel by viewModels {
        viewModelFactory {
            initializer {
                dev.fughetta.dshcompose.ui.shell.ShellPrefsViewModel(
                    (application as ClientApplication).settingsRepository,
                )
            }
        }
    }
    private val imagePreviewModel:
        dev.fughetta.dshcompose.ui.images.ImagePreviewViewModel by viewModels {
        viewModelFactory {
            initializer {
                dev.fughetta.dshcompose.ui.images.ImagePreviewViewModel(
                    (application as ClientApplication).messageAttachmentsRepository,
                )
            }
        }
    }
    private val modelDefaultsModel
        get() = model.modelsModel

    private val providersModel:
        dev.fughetta.dshcompose.ui.providers.ProvidersViewModel by viewModels {
        viewModelFactory {
            initializer {
                dev.fughetta.dshcompose.ui.providers.ProvidersViewModel(
                    (application as ClientApplication).providersRepository,
                )
            }
        }
    }
    private val subscriptionsModel:
        dev.fughetta.dshcompose.ui.providers.SubscriptionsViewModel by viewModels {
        viewModelFactory {
            initializer {
                dev.fughetta.dshcompose.ui.providers.SubscriptionsViewModel(
                    (application as ClientApplication).subscriptionsRepository,
                )
            }
        }
    }
    private val agentsModel: dev.fughetta.dshcompose.ui.agents.AgentsViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as ClientApplication
                val root = model
                val repository = app.agentTeamsRepository
                dev.fughetta.dshcompose.ui.agents.AgentsViewModel(
                    repository,
                    app.sessionsRepository,
                    failureMessage = { AppStrings.get(app, R.string.agents_failed, it) },
                    restartMessage = { AppStrings.get(app, R.string.agents_restart_required) },
                    onOpen = { owner, id ->
                        if (repository.value === owner)
                            root.client?.let { root.revealOwnedSession(it, id) }
                    },
                    onError = { code -> root.mutableState.update { it.copy(error = code) } },
                )
            }
        }
    }
    private val agentPresetsModel:
        dev.fughetta.dshcompose.ui.presets.AgentPresetsViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application
                val repository = (app as ClientApplication).agentPresetsRepository
                val root = model
                dev.fughetta.dshcompose.ui.presets.AgentPresetsViewModel(
                    repository,
                    message = { AppStrings.get(app, it) },
                    copyName = { AppStrings.get(app, R.string.preset_copy_name, it) },
                    requestText = { files, replacing ->
                        val bundle = files.definition
                        if (replacing == null)
                            AppStrings.get(
                                app,
                                R.string.preset_request_create,
                                bundle.name,
                                bundle.id,
                                files.directory,
                            )
                        else
                            AppStrings.get(
                                app,
                                R.string.preset_request_update,
                                bundle.name,
                                bundle.id,
                                replacing,
                                files.directory,
                            )
                    },
                    openSession = { owner, id ->
                        if (repository.value === owner)
                            root.client?.let { root.revealOwnedSession(it, id) }
                    },
                )
            }
        }
    }
    private val settingsModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer { SettingsViewModel((application as ClientApplication).settingsRepository) }
        }
    }

    /** The language this activity's resources are in; another choice recreates it. */
    private var shownLanguage: String? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        shownLanguage = dev.fughetta.dshcompose.i18n.AppStrings.language(newBase)
        super.attachBaseContext(dev.fughetta.dshcompose.i18n.AppStrings.wrap(newBase))
    }

    private lateinit var phoneBinding: dev.fughetta.dshcompose.phoneui.PhoneActivityBinding
    private var mediaStorageWait: CompletableDeferred<Boolean>? = null
    private val mediaStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            mediaStorageWait?.complete(it)
        }

    private suspend fun requestMediaStorage(): Boolean {
        if (
            android.os.Build.VERSION.SDK_INT >= 29 ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        )
            return true
        if (mediaStorageWait != null) return false
        val pending = CompletableDeferred<Boolean>()
        mediaStorageWait = pending
        return try {
            mediaStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            withTimeoutOrNull(65_000) { pending.await() } ?: false
        } finally {
            if (mediaStorageWait === pending) mediaStorageWait = null
        }
    }

    private val pluginFiles =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.nativePluginFileResult(it)
        }
    private var capturePath: String? = null
    private val capturePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val photo = capturePath?.let(::File)
            capturePath = null
            if (success && photo != null) {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", photo)
                model.importPickedFiles(listOf(uri)) { photo.delete() }
            } else {
                photo?.delete()
                model.importPickedFiles(emptyList())
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("composer.capturePath", capturePath)
        super.onSaveInstanceState(outState)
    }

    private val photos =
        registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(
                dev.fughetta.dshcompose.domain.attachments.AttachmentLimits.MAX_FILES,
            ),
        ) { uris ->
            model.importPickedFiles(uris)
        }

    private val files =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
            { uris -> model.importPickedFiles(uris) },
        )

    private val workspaceExport =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            model.workspaceExportDestination(uri)
        }

    private val archiveStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.archivePermissionResult(it)
        }

    private val imageStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.imagePermissionResult(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        LaunchStart.onActivityCreate(this)
        super.onCreate(savedInstanceState)
        capturePath = savedInstanceState?.getString("composer.capturePath")
        enableEdgeToEdge()
        current = WeakReference(this)
        dev.fughetta.dshcompose.engineui.EngineActivityBinding(this, model.engineControls)
        model.mediaStoragePermission(::requestMediaStorage)
        model.attachNativePlugins(this, { pluginFiles.launch(it) })
        phoneBinding = dev.fughetta.dshcompose.phoneui.PhoneActivityBinding(this)
        dev.fughetta.dshcompose.phoneui.PerformanceActivityBinding(
            this,
            lifecycleScope,
            model.performanceState,
        )
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state
                    .map { it.imageExport }
                    .distinctUntilChanged()
                    .collect { export ->
                        if (
                            export != null &&
                                export.needsPermission &&
                                model.claimImagePermission(export.id)
                        ) {
                            try {
                                imageStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } catch (_: Exception) {
                                model.imagePermissionResult(false)
                            }
                        }
                        val shared = export?.share
                        if (export != null && shared != null && model.claimImageShare(export.id)) {
                            val launched =
                                try {
                                    val uri =
                                        FileProvider.getUriForFile(
                                            this@MainActivity,
                                            "$packageName.files",
                                            File(shared.path),
                                        )
                                    val send =
                                        Intent(Intent.ACTION_SEND)
                                            .setType(shared.mime)
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    send.clipData =
                                        ClipData.newUri(contentResolver, shared.name, uri)
                                    startActivity(
                                        Intent.createChooser(
                                            send,
                                            getString(
                                                dev.fughetta.dshcompose.R.string.image_share,
                                            ),
                                        ),
                                    )
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            model.imageShared(export.id, launched)
                        }
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state
                    .map { it.sessionExport }
                    .distinctUntilChanged()
                    .collect { export ->
                        if (
                            export != null &&
                                export.needsPermission &&
                                model.claimArchivePermission(export.id)
                        ) {
                            try {
                                archiveStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } catch (_: Exception) {
                                model.archivePermissionResult(false)
                            }
                        }
                        val shared = export?.share
                        if (
                            export != null && shared != null && model.claimArchiveShare(export.id)
                        ) {
                            val launched =
                                try {
                                    val uri =
                                        FileProvider.getUriForFile(
                                            this@MainActivity,
                                            "$packageName.files",
                                            File(shared.path),
                                        )
                                    val send =
                                        Intent(Intent.ACTION_SEND)
                                            .setType("application/zip")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    send.clipData =
                                        ClipData.newUri(contentResolver, shared.name, uri)
                                    startActivity(
                                        Intent.createChooser(
                                            send,
                                            getString(
                                                dev.fughetta.dshcompose.R.string
                                                    .session_export_share,
                                            ),
                                        ),
                                    )
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            model.archiveShared(export.id, launched)
                        }
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    jobsModel.uiState.collect { view ->
                        model.applyJobs(view)
                        jobsModel.consumeShare()?.let { request ->
                            try {
                                val uri =
                                    FileProvider.getUriForFile(
                                        this@MainActivity,
                                        "$packageName.files",
                                        request.file,
                                    )
                                val intent =
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.clipData =
                                    ClipData.newUri(contentResolver, "task-output.txt", uri)
                                startActivity(
                                    Intent.createChooser(
                                        intent,
                                        getString(CoreDesignsystemR.string.jobs_share_output),
                                    ),
                                )
                            } catch (_: Exception) {
                                jobsModel.shareFailed(request.id)
                            }
                        }
                        view?.export?.saveRequest?.let { request ->
                            if (jobsModel.savePickerOpened(request.id)) {
                                try {
                                    saveJobOutput.launch(request.name)
                                } catch (_: Exception) {
                                    jobsModel.saveDocument(null, failed = true)
                                }
                            }
                        }
                    }
                }
                launch { goalsModel.uiState.collect(model::applyGoal) }
                launch { settingsModel.uiState.collect(model::applySettings) }
                launch { shellPreferencesModel.uiState.collect(model::applyShellPreferences) }
                launch {
                    kotlinx.coroutines.flow
                        .combine(model.state, model.sessionRepository.state) { state, catalog ->
                            SchedulesContext(
                                state.workspaces.rows
                                    .map { SchedulesWorkspace(it.id, it.title, it.path) }
                                    .toPersistentList(),
                                catalog.sessions
                                    .map { SchedulesSession(it.sessionId, it.title) }
                                    .toPersistentList(),
                                state.workspaces.selectedId,
                                model.watchedId,
                            )
                        }
                        .distinctUntilChanged()
                        .collect(schedulesModel::context)
                }
                launch {
                    schedulesModel.uiState.collect { value ->
                        model.applySchedules(value)
                        schedulesModel.consumeOpenSession()?.let(model::openScheduledSession)
                    }
                }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(state.language) {
                if (state.language != shownLanguage) recreate()
            }
            dev.fughetta.dshcompose.appearance.AppearanceActivityEffects(
                model.appearanceController,
            )
            dev.fughetta.dshcompose.transcript.TranscriptArtifactEffects(
                model.transcriptArtifacts,
                model::previewTranscriptFile,
            )

            AppShell(
                state,
                model.commandPalette,
                model.conversationState,
                searchModel.uiState,
                sessionsModel.uiState,
                model.drawerUi.state,
                model.sessionsModel.deletionState,
                model.sessionsModel.dialogState,
                model.conversationModel.messageMenu,
                imagePreviewModel.uiState,
                model.modelsModel.pickerUiState,
                modelDefaultsModel.uiState,
                providersModel.uiState,
                subscriptionsModel.uiState,
                agentPresetsModel.uiState,
                agentsModel.uiState,
                model.replyImageLoader,
                model.codeHighlighter,
                model::claimPhonePermission,
                loadNativePluginAsset = model::nativePluginAsset,
                claimNotificationPermission = model::claimNotificationPermission,
                reasoningTranslation = model.pendingPrompts.translation,
                platformAppearance = {
                    dev.fughetta.dshcompose.appearance.AppearanceLaunchPreview(
                        state.appearance,
                    ) {
                        model.onAction(AppAction.Appearance(it))
                    }
                    if (!state.appearanceLaunchFinished)
                        dev.fughetta.dshcompose.appearance.AppearanceLaunchOverlay(
                            state.appearance,
                            ready = true,
                            onFinished = { model.onAction(AppAction.FinishAppearanceLaunch) },
                            modifier = Modifier.fillMaxSize(),
                        )
                },
            ) { action ->
                if (
                    agentsModel.handle(action) ||
                        agentPresetsModel.handle(action) ||
                        subscriptionsModel.handle(action) ||
                        providersModel.handle(action) ||
                        modelDefaultsModel.handle(action) ||
                        imagePreviewModel.handle(action) ||
                        searchModel.handle(action) ||
                        goalsModel.handle(action) ||
                        jobsModel.handle(action)
                )
                    return@AppShell
                if (action is AppAction.OpenSearchSession && !searchModel.canOpen(action.epoch))
                    return@AppShell
                if (action is AppAction.Schedules) {
                    schedulesModel.dispatch(action.action)
                } else if (action is AppAction.ShellPrefs) {
                    shellPreferencesModel.onAction(action.action)
                } else if (action is AppAction.ChangeLanguage) {
                    dev.fughetta.dshcompose.i18n.AppStrings.setLanguage(
                        application,
                        action.language,
                    )
                    model.applyLanguage(action.language)
                } else if (action is AppAction.ChangeTheme) {
                    settingsModel.theme(action.theme)
                } else if (action is AppAction.ChangeDynamicColor) {
                    settingsModel.dynamicColor(action.enabled)
                } else if (action is AppAction.FoldCompletedTurns) {
                    settingsModel.foldCompletedTurns(action.value)
                } else if (action is AppAction.ExportWorkspaceFile) {
                    model.prepareWorkspaceExport(action.sessionId, action.path)?.let { name ->
                        try {
                            workspaceExport.launch(name)
                        } catch (_: Exception) {
                            model.workspaceExportPickerFailed()
                        }
                    }
                } else if (action is AppAction.PickAttachments) {
                    if (model.prepareFileSelection(action.sessionId)) {
                        try {
                            if (
                                action.kind ==
                                    dev.fughetta.dshcompose.ui.ComposerAttachmentKind.Photos
                            ) {
                                photos.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            } else {
                                files.launch(
                                    if (
                                        action.kind ==
                                            dev.fughetta.dshcompose.ui.ComposerAttachmentKind.Media
                                    )
                                        arrayOf("video/*", "audio/*")
                                    else arrayOf("*/*"),
                                )
                            }
                        } catch (_: Exception) {
                            model.importPickedFiles(emptyList())
                            android.widget.Toast.makeText(
                                    this,
                                    R.string.composer_picker_failed,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                } else if (action is AppAction.CapturePhoto) {
                    if (model.prepareFileSelection(action.sessionId)) {
                        try {
                            val directory = File(cacheDir, "composer-capture").apply { mkdirs() }
                            val photo = File.createTempFile("photo-", ".jpg", directory)
                            capturePath = photo.absolutePath
                            capturePhoto.launch(
                                FileProvider.getUriForFile(this, "$packageName.files", photo),
                            )
                        } catch (_: Exception) {
                            capturePath?.let { File(it).delete() }
                            capturePath = null
                            model.importPickedFiles(emptyList())
                            android.widget.Toast.makeText(
                                    this,
                                    R.string.composer_picker_failed,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        }
                    }
                } else if (action == AppAction.OpenImageSettings) {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null),
                        ),
                    )
                } else model.onAction(action)
            }
        }
        if (
            savedInstanceState == null &&
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        )
            model.handleIntent(intent)
        LaunchThemeSync.sync(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!phoneBinding.handleIntent(intent)) model.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        model.foreground(true)
        dev.fughetta.dshcompose.phone.background.BackgroundControl.get(applicationContext)
            .restoreKeptChannel()
    }

    override fun onPause() {
        inForeground = false
        model.foreground(false)
        super.onPause()
    }

    override fun onStop() {

        super.onStop()
    }

    override fun onDestroy() {
        mediaStorageWait?.complete(false)
        mediaStorageWait = null
        if (current?.get() === this) {
            model.attachNativePlugins(null)
            model.mediaStoragePermission(null)
            current = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var inForeground = false
            private set

        @Volatile
        var current: WeakReference<MainActivity>? = null
            private set
    }
}

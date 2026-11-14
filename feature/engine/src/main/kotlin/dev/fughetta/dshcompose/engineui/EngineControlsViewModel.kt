package dev.fughetta.dshcompose.engineui

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.engine.runtime.*
import dev.fughetta.dshcompose.runtime.*
import dev.fughetta.dshcompose.ui.runtime.*
import java.io.File
import kotlin.coroutines.resume
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

sealed interface EngineControlsEffect {
    data object OpenHostServices : EngineControlsEffect

    data object OpenProviders : EngineControlsEffect

    data object OpenAccount : EngineControlsEffect

    data object OpenAppearance : EngineControlsEffect

    data object OpenPhoneAccess : EngineControlsEffect

    data object UseLocal : EngineControlsEffect

    data object OnboardingFinished : EngineControlsEffect

    data object LocalHistoryRemoved : EngineControlsEffect
}

class EngineControlsViewModel(
    context: Context,
    private val runtime: RuntimeController,
    private val beginInstall: () -> Unit,
    private val finishInstall: (Boolean) -> Unit,
    parent: CoroutineScope? = null,
    private val phoneStorage: () -> Boolean,
    private val hasModelAccess: () -> Boolean,
    private val extraDiagnostics: () -> Map<String, Any?> = { emptyMap() },
) : ViewModel() {
    private val parentScope = parent ?: viewModelScope

    override fun onCleared() {
        close()
    }

    private val app = context.applicationContext
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
        )
    private val transitions = RuntimeTransitions(runtime)
    private val prefs = app.localPreferences("dsh_native_onboarding")
    private val _state =
        MutableStateFlow(
            EngineControlsState(
                setup =
                    runCatching { SetupStep.valueOf(prefs.getString("step", null) ?: "Welcome") }
                        .getOrDefault(SetupStep.Welcome),
                onboardingComplete = prefs.getBoolean("complete", false),
                localDeclined = prefs.getBoolean("localDeclined", false),
            ),
        )
    val state: StateFlow<EngineControlsState> = _state.asStateFlow()
    private val channel = Channel<EngineControlsEffect>(Channel.BUFFERED)
    val effects: Flow<EngineControlsEffect> = channel.receiveAsFlow()
    internal var activity: EngineActivityBinding? = null
    internal var pendingDiagnosticsSave: File? = null
    private var operation: Job? = null
    private var pendingConnection: Job? = null
    private var connectionRequest: Any? = null
    private val foreground = MutableStateFlow(false)
    private var logsThrough = 0L
    private val sink = RuntimeController.EventSink { event ->
        when (event["type"]) {
            "status" -> {
                refreshStatus()
            }
            "progress" ->
                _state.update {
                    it.copy(
                        installStep = event["step"] as? String,
                        installDone = (event["done"] as? Number)?.toLong() ?: 0,
                        installTotal = (event["total"] as? Number)?.toLong() ?: 0,
                    )
                }
            "logs" -> appendLogs(event["entries"])
        }
    }

    init {
        runtime.addSink(sink)
        refreshStatus()
        loadLogs()
        val memory =
            ActivityManager.MemoryInfo().also {
                (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(
                    it,
                )
            }
        _state.update {
            it.copy(
                memoryBytes = memory.totalMem,
                freeBytes = app.filesDir.usableSpace,
                requiredBytes = runCatching { runtime.manifest.installationBytes }.getOrDefault(0),
            )
        }
    }

    private fun emit(effect: EngineControlsEffect) {
        scope.launch { channel.send(effect) }
    }

    private fun error(code: String) {
        _state.update { it.copy(error = code) }
    }

    private fun step(next: SetupStep) {
        prefs.edit().putString("step", next.name).apply()
        _state.update { it.copy(setup = next) }
    }

    private fun refreshStatus() {
        val value = runtime.status()
        val failure = value["failure"] as? Map<*, *>
        fun failureList(key: String) =
            (failure?.get(key) as? List<*>)
                ?.take(64)
                ?.map { EngineDiagnostics.clean(it.toString()) }
                .orEmpty()
                .toPersistentList()
        _state.update {
            it.copy(
                phase = runtime.phase.wire,
                mode = value["mode"] as? String ?: "normal",
                error =
                    (value["error"] as? String)?.let(EngineDiagnostics::clean)
                        ?: it.error?.takeIf { code -> code.startsWith("engine/") },
                failure = failure?.get("summary")?.toString()?.let(EngineDiagnostics::clean),
                failureKind = failure?.get("kind") as? String,
                failureExit = (failure?.get("exitCode") as? Number)?.toInt(),
                failureRequired = failureList("requiredRows"),
                failureSuspects = failureList("suspects"),
                bundledVersion = value["bundledVersion"] as? String,
                installStep = value["installStep"] as? String,
                installDone = (value["installDone"] as? Number)?.toLong() ?: 0L,
                installTotal = (value["installTotal"] as? Number)?.toLong() ?: 0L,
                installedVersion = value["installedVersion"] as? String,
                executionMode = value["currentExecutionMode"] as? String,
            )
        }
    }

    private fun run(name: String, block: suspend () -> Unit): Job? {
        if (operation?.isActive == true) return null
        val job = scope.launch {
            _state.update { it.copy(busy = true, operation = name, error = null) }
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                error("engine/$name-timeout")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error("engine/$name-failed")
            } finally {
                _state.update { it.copy(busy = false, operation = null) }
                refreshStatus()
            }
        }
        operation = job
        return job
    }

    private fun openConnection(effect: EngineControlsEffect) {
        pendingConnection?.cancel()
        val request = Any()
        connectionRequest = request
        pendingConnection = scope.launch {
            var starting: Job? = null
            _state.update { it.copy(waitingForConnection = true, error = null) }
            try {
                operation?.join()
                check(
                    runtime.phase in
                        setOf(
                            RuntimeController.Phase.INSTALLED,
                            RuntimeController.Phase.RUNNING,
                            RuntimeController.Phase.STARTING,
                        ),
                ) {
                    "engine/install-not-ready"
                }
                foreground.first { it }
                starting =
                    run("connect") {
                        transitions.start(phoneStorage())
                        foreground.first { it }
                        if (connectionRequest === request) {
                            emit(EngineControlsEffect.UseLocal)
                            emit(effect)
                        }
                    }
                starting?.join()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error("engine/connect-not-ready")
            } finally {
                starting?.cancel()
                if (connectionRequest === request) {
                    connectionRequest = null
                    _state.update { it.copy(waitingForConnection = false) }
                }
            }
        }
    }

    fun install(dropKept: Boolean = false) {
        if (runtime.phase == RuntimeController.Phase.INSTALLING) return error("engine/install-busy")
        if (
            runtime.phase in
                setOf(
                    RuntimeController.Phase.RUNNING,
                    RuntimeController.Phase.STARTING,
                    RuntimeController.Phase.STOPPING,
                )
        )
            return error("engine/stop-before-install")
        run("install") {
            prefs.edit().putBoolean("localDeclined", false).apply()
            _state.update { it.copy(localDeclined = false) }
            beginInstall()
            val failure =
                suspendCancellableCoroutine<Throwable?> { continuation ->
                    runtime.install(dropKept) { problem ->
                        val notificationFailure = runCatching {
                            finishInstall(problem == null)
                        }
                            .exceptionOrNull()
                        if (
                            problem == null &&
                                prefs.getBoolean("complete", false) &&
                                !prefs.getBoolean("localDeclined", false)
                        ) {
                            runtime.start(emptyMap(), phoneStorage = phoneStorage())
                        }
                        if (continuation.isActive)
                            continuation.resume(problem ?: notificationFailure)
                    }
                }
            if (failure != null) {
                error(
                    if (failure.message?.startsWith("keep:") == true) "engine/kept-data-failed"
                    else "engine/install-failed",
                )
                if (failure.message?.startsWith("keep:") == true)
                    _state.update { it.copy(confirmation = "drop-kept") }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendLogs(raw: Any?) {
        val entries =
            (raw as? List<*>)
                ?.mapNotNull { it as? Map<String, Any?> }
                .orEmpty()
                .mapNotNull { row ->
                    val seq = (row["seq"] as? Number)?.toLong() ?: return@mapNotNull null
                    if (seq <= logsThrough) return@mapNotNull null
                    EngineLogLine(
                        seq,
                        EngineDiagnostics.clean(row["line"]?.toString().orEmpty()),
                        row["stream"]?.toString().orEmpty(),
                    )
                }
        if (entries.isEmpty()) return
        logsThrough = maxOf(logsThrough, entries.maxOf { it.sequence })
        _state.update { it.copy(logs = (it.logs + entries).takeLast(2000).toPersistentList()) }
    }

    private fun loadLogs() {
        val snapshot = runtime.logSnapshot(2000)
        appendLogs(snapshot["entries"])
    }

    private fun confirm() {
        val kind = state.value.confirmation ?: return
        _state.update { it.copy(confirmation = null) }
        when (kind) {
            "reset",
            "uninstall" ->
                run(kind) {
                    val keep = buildSet {
                        if (kind != "uninstall" && state.value.keepUiAndSkills)
                            add(ResetKeep.UI_AND_SKILLS)
                        if (kind != "uninstall" && state.value.keepPluginsAndSettings)
                            add(ResetKeep.PLUGINS_AND_SETTINGS)
                    }
                    val previousDeclined = state.value.localDeclined
                    if (kind == "uninstall") {
                        prefs.edit().putBoolean("localDeclined", true).apply()
                        _state.update { it.copy(localDeclined = true) }
                    }
                    try {
                        transitions.reset(keep)
                        if (kind == "reset") {
                            prefs.edit().putBoolean("complete", false).apply()
                            _state.update {
                                it.copy(onboardingComplete = false, page = EnginePage.Setup)
                            }
                            step(SetupStep.Finishing)
                        }
                        emit(EngineControlsEffect.LocalHistoryRemoved)
                    } catch (problem: Exception) {
                        if (kind == "uninstall") {
                            prefs.edit().putBoolean("localDeclined", previousDeclined).apply()
                            _state.update { it.copy(localDeclined = previousDeclined) }
                        }
                        throw problem
                    }
                }
            "drop-kept" -> install(true)
            "restore" -> run("restore") { transitions.restore(phoneStorage()) }
        }
    }

    fun modelAccessChanged() {
        if (
            !state.value.onboardingComplete &&
                state.value.setup == SetupStep.Connect &&
                hasModelAccess()
        )
            step(SetupStep.Finishing)
    }

    fun action(action: EngineControlsAction) {
        when (action) {
            is EngineControlsAction.Show -> {
                _state.update { it.copy(page = action.page) }
                when (action.page) {
                    EnginePage.Logs -> loadLogs()
                    else -> Unit
                }
            }
            EngineControlsAction.Refresh -> {
                refreshStatus()
            }
            EngineControlsAction.Welcome -> step(SetupStep.Choice)
            EngineControlsAction.ChooseLocal -> {
                prefs.edit().putBoolean("localDeclined", false).apply()
                _state.update { it.copy(localDeclined = false) }
                step(SetupStep.Appearance)
                if (
                    runtime.phase == RuntimeController.Phase.NOT_INSTALLED ||
                        runtime.phase == RuntimeController.Phase.ERROR
                )
                    install()
            }
            EngineControlsAction.ChooseRemote -> {
                prefs.edit().putBoolean("localDeclined", true).putBoolean("complete", true).apply()
                _state.update {
                    it.copy(
                        localDeclined = true,
                        onboardingComplete = true,
                        page = EnginePage.Overview,
                    )
                }
                step(SetupStep.Finished)
                emit(EngineControlsEffect.OpenHostServices)
            }
            EngineControlsAction.ContinueSetup ->
                when (state.value.setup) {
                    SetupStep.Appearance -> step(SetupStep.Access)
                    SetupStep.Access -> step(SetupStep.Connect)
                    SetupStep.Connect ->
                        if (hasModelAccess()) step(SetupStep.Finishing)
                        else openConnection(EngineControlsEffect.OpenProviders)
                    SetupStep.Finishing -> {
                        prefs.edit().putBoolean("complete", true).apply()
                        step(SetupStep.Finished)
                        _state.update {
                            it.copy(onboardingComplete = true, page = EnginePage.Overview)
                        }
                        if (runtime.phase == RuntimeController.Phase.INSTALLED)
                            runtime.start(emptyMap(), phoneStorage = phoneStorage())
                        emit(EngineControlsEffect.UseLocal)
                        emit(EngineControlsEffect.OnboardingFinished)
                    }
                    else -> Unit
                }
            EngineControlsAction.SkipProvider -> {
                pendingConnection?.cancel()
                connectionRequest = null
                _state.update { it.copy(waitingForConnection = false) }
                prefs.edit().putBoolean("keySkipped", true).apply()
                step(SetupStep.Finishing)
            }
            EngineControlsAction.OpenProviders -> openConnection(EngineControlsEffect.OpenProviders)
            EngineControlsAction.OpenAccount -> openConnection(EngineControlsEffect.OpenAccount)
            EngineControlsAction.OpenAppearance -> emit(EngineControlsEffect.OpenAppearance)
            EngineControlsAction.OpenPhoneAccess -> emit(EngineControlsEffect.OpenPhoneAccess)
            EngineControlsAction.Install -> install()
            EngineControlsAction.Start -> run("start") { transitions.start(phoneStorage()) }
            EngineControlsAction.Stop -> run("stop") { transitions.stop() }
            EngineControlsAction.Restart ->
                run("restart") {
                    transitions.stop()
                    transitions.start(phoneStorage())
                }
            EngineControlsAction.Restore ->
                if (state.value.restore.available)
                    _state.update { it.copy(confirmation = "restore") }
            EngineControlsAction.Reset -> _state.update { it.copy(confirmation = "reset") }
            EngineControlsAction.Uninstall -> _state.update { it.copy(confirmation = "uninstall") }
            EngineControlsAction.DropKeptAndInstall ->
                _state.update { it.copy(confirmation = "drop-kept") }
            EngineControlsAction.Confirm -> confirm()
            EngineControlsAction.DismissConfirmation ->
                _state.update { it.copy(confirmation = null) }
            EngineControlsAction.ClearFailure -> runtime.clearFailure()
            is EngineControlsAction.ResetKeep ->
                _state.update {
                    it.copy(
                        keepUiAndSkills = action.uiAndSkills,
                        keepPluginsAndSettings = action.pluginsAndSettings,
                    )
                }
            is EngineControlsAction.FollowLogs ->
                _state.update { it.copy(followLogs = action.enabled) }
            EngineControlsAction.CollectDiagnostics ->
                run("diagnostics") {
                    val extra = extraDiagnostics()
                    val text =
                        withContext(Dispatchers.IO) {
                            EngineDiagnostics.collect(app, runtime, extra)
                        }
                    _state.update {
                        it.copy(
                            diagnosticsPreview = text,
                            diagnosticsRevision = it.diagnosticsRevision + 1,
                            diagnosticsReviewed = false,
                            diagnosticsResult = null,
                        )
                    }
                }
            is EngineControlsAction.ReviewDiagnostics ->
                _state.update {
                    if (action.revision == it.diagnosticsRevision)
                        it.copy(diagnosticsReviewed = action.reviewed)
                    else it
                }
            EngineControlsAction.ShareDiagnostics -> export(false)
            EngineControlsAction.SaveDiagnostics -> export(true)
            EngineControlsAction.PublishDiagnostics ->
                run("publish") {
                    val snapshot = state.value
                    val preview = snapshot.diagnosticsPreview
                    if (!snapshot.diagnosticsReviewed || preview == null)
                        error("engine/diagnostics-review-required")
                    else {
                        val result =
                            withContext(Dispatchers.IO) { runtime.publishDiagnostics(preview) }
                        _state.update {
                            it.copy(
                                diagnosticsResult =
                                    if (result == null) "engine/publish-failed"
                                    else "engine/published",
                            )
                        }
                    }
                }
        }
    }

    private fun export(save: Boolean) =
        run("export") {
            val snapshot = state.value
            val preview = snapshot.diagnosticsPreview
            if (!snapshot.diagnosticsReviewed || preview == null) {
                error("engine/diagnostics-review-required")
                return@run
            }
            val file =
                withContext(Dispatchers.IO) {
                    EngineDiagnostics.archive(
                        app,
                        preview,
                        snapshot.diagnosticsRevision,
                    )
                }
            if (activity?.export(file, save) != true) error("engine/export-unavailable")
        }

    internal fun saveDiagnostics(source: File, uri: android.net.Uri) {
        scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                        app.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            source.inputStream().use { it.copyTo(output) }
                        } ?: error("engine/save-unavailable")
                    }
                        .isSuccess
                }
            exportResult(ok)
        }
    }

    internal fun exportResult(ok: Boolean) {
        _state.update {
            it.copy(diagnosticsResult = if (ok) "engine/saved" else "engine/save-cancelled")
        }
    }

    fun onAppResumed() {
        foreground.value = true
        refreshStatus()
    }

    fun onAppPaused() {
        foreground.value = false
    }

    fun close() {
        runtime.removeSink(sink)
        transitions.close()
        activity = null
        scope.cancel()
        channel.close()
    }
}

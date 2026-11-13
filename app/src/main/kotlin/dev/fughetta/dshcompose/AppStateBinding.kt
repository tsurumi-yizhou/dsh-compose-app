package dev.fughetta.dshcompose

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.AppViewModel.MediaPublication
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.connection.ConnectMode
import dev.fughetta.dshcompose.connection.changeConnection
import dev.fughetta.dshcompose.connection.connectLocal
import dev.fughetta.dshcompose.connection.connectService
import dev.fughetta.dshcompose.connection.status
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import dev.fughetta.dshcompose.messages.clearDeliveredDraft
import dev.fughetta.dshcompose.messages.refreshPendingPrompts
import dev.fughetta.dshcompose.nativeplugins.handleNativePluginEffect
import dev.fughetta.dshcompose.sessions.refreshSessionAttention
import dev.fughetta.dshcompose.transcript.handleMediaEffect
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.bindState() {
    run {
        mutableState.update {
            it.copy(
                hasVirtualizationFramework =
                    app.packageManager.hasSystemFeature(
                        "android.software.virtualization_framework",
                    ),
            )
        }
        viewModelScope.launch {
            try {
                val hosts = remoteHostsRepository.loadServices()
                mutableState.update { it.copy(remoteHosts = hosts, remoteHostsBusy = false) }
                if (selectedHost.startsWith("compose-")) connectService(selectedHost)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(remoteHostsBusy = false, error = "service/read-failed")
                }
            }
        }
        viewModelScope.launch {
            var previous: dev.fughetta.dshcompose.ui.sessions.DrawerUiState? = null
            drawerUi.state.collect { next ->
                val before = previous
                previous = next
                if (next.failureRevision > 0 && next.failureRevision != before?.failureRevision)
                    mutableState.update { it.copy(error = next.failure) }
                if (next.loaded && (before?.loaded != true || next.seen != before.seen))
                    refreshSessionAttention()
            }
        }
        viewModelScope.launch {
            var previousAll: List<SessionSummary>? = null
            var previousRefresh = 0L
            sessionRepository.state.collect { catalog ->
                val failure = catalog.failure
                if (catalog.refreshRevision != previousRefresh) {
                    previousRefresh = catalog.refreshRevision
                    if (catalog.refreshRevision > 0 && failure == null) {
                        val known = catalog.sessions.mapTo(hashSetOf()) { it.sessionId }
                        mutateDrawer { it.forgetMissing(known) }
                    }
                }
                if (catalog.allSessions !== previousAll) {
                    previousAll = catalog.allSessions
                    attentionSessions = catalog.allSessions
                    if (selectedHost != "local")
                        notificationSettings.sessions(selectedHost, catalog.allSessions)
                    refreshSessionAttention()
                }
            }
        }
        viewModelScope.launch {
            var errorRevision = 0L
            composer.state.collect { saved ->
                conversations.syncComposer()
                mutableState.update { app ->
                    app.copy(
                        error =
                            if (saved.storageErrorRevision != errorRevision) "drafts/storage-failed"
                            else app.error,
                    )
                }
                errorRevision = saved.storageErrorRevision
            }
        }
        updateChat(DRAFT_SESSION_ID) { it.copy(syncing = false) }
        viewModelScope.launch {
            sessionRepository.state
                .map { it.sessions }
                .distinctUntilChanged { a, b -> a === b }
                .collect { refreshSessionAttention() }
        }
        viewModelScope.launch {
            composerAttachments.state.collect { conversations.syncAttachments() }
        }
        viewModelScope.launch { pendingPrompts.delivered.collect(::clearDeliveredDraft) }
        viewModelScope.launch {
            pendingPrompts.state.collect { pending ->
                val retained = pending.values.flatMap { it.files }.mapTo(mutableSetOf()) { it.id }
                composerAttachments.allFiles().forEach { retained.add(it.id) }
                connectionSession?.messageAttachments?.retain(retained)
                watchedId?.let(::refreshPendingPrompts)
            }
        }
        collectInto(usage.state) { state, value -> state.copy(usage = value) }
        collectInto(replyPreferences.state) { state, value -> state.copy(replyPrefs = value) }
        collectInto(transcriptArtifacts.state) { state, value ->
            state.copy(transcriptArtifact = value)
        }
        collectInto(backgroundNet.state) { state, value -> state.copy(backgroundNet = value) }
        if (!engineControls.state.value.onboardingComplete)
            engineControls.action(
                dev.fughetta.dshcompose.ui.runtime.EngineControlsAction.Show(
                    dev.fughetta.dshcompose.ui.runtime.EnginePage.Setup,
                ),
            )
        mutableState.update { it.copy(engineControls = engineControls.state.value) }
        collectInto(engineControls.state) { state, value -> state.copy(engineControls = value) }
        viewModelScope.launch {
            engineControls.effects.collect { effect ->
                when (effect) {
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.OpenHostServices ->
                        openFeature("compose-services")
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.OpenProviders ->
                        openFeature("providers")
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.OpenAccount ->
                        openFeature("account")
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.OpenAppearance ->
                        onAction(AppAction.OpenAppearance)
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.OpenPhoneAccess ->
                        openFeature("phone")
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.UseLocal -> connectLocal()
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.OnboardingFinished ->
                        openFeature("conversations")
                    dev.fughetta.dshcompose.engineui.EngineControlsEffect.LocalHistoryRemoved -> {
                        settings.rememberSession("local", null)
                        if (selectedHost == "local") {
                            val previous = watchedId
                            watchedId = null
                            conversations.state.value.chats.keys.forEach(composer::forget)
                            val files = composerAttachments.clear()
                            pendingPrompts.state.value.values
                                .map { it.owner }
                                .distinct()
                                .filter { it.host == "local" }
                                .forEach(pendingPrompts::sessionDeleted)
                            viewModelScope.launch { composerFiles.release(files) }
                            changeConnection("local", ConnectMode.Offline)
                            conversations.reset()
                            mutableState.update {
                                it.copy(
                                    openSessionId = null,
                                    deletedSessionId = previous,
                                )
                            }
                            if (!engineControls.state.value.onboardingComplete)
                                openFeature("engine")
                        }
                    }
                }
            }
        }
        mutableState.bind(
            viewModelScope,
            deepSeekAccount.state,
            { state, value -> state.copy(deepSeekAccount = value) },
        ) {
            refreshUsageAccount()
        }
        viewModelScope.launch {
            deepSeekAccount.effects.collect { effect ->
                when (effect) {
                    dev.fughetta.dshcompose.ui.account.DeepSeekAccountEffect.StartLocalEngine -> {
                        if (
                            runtime.phase in
                                setOf(
                                    RuntimeController.Phase.INSTALLED,
                                    RuntimeController.Phase.ERROR,
                                )
                        )
                            onAction(AppAction.StartRuntime)
                        else if (runtime.phase != RuntimeController.Phase.RUNNING)
                            mutableState.update {
                                it.copy(
                                    featurePage = "engine",
                                    featurePageRevision = it.featurePageRevision + 1,
                                )
                            }
                    }
                    is dev.fughetta.dshcompose.ui.account.DeepSeekAccountEffect.Browser -> {
                        val lease = localObserver.lease ?: return@collect
                        if (lease.generation != effect.generation) return@collect
                        val opened =
                            try {
                                app.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(effect.url))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                true
                            } catch (_: Exception) {
                                false
                            }
                        deepSeekAccount.action(
                            dev.fughetta.dshcompose.ui.account.DeepSeekAccountAction.BrowserOpened(
                                opened,
                            ),
                        )
                    }
                    dev.fughetta.dshcompose.ui.account.DeepSeekAccountEffect.ApiKeys -> {
                        if (selectedHost != "local") connectLocal()
                        mutableState.update {
                            it.copy(
                                featurePage = "providers",
                                featurePageRevision = it.featurePageRevision + 1,
                            )
                        }
                    }
                    dev.fughetta.dshcompose.ui.account.DeepSeekAccountEffect.IdentityChanged -> {
                        engineControls.modelAccessChanged()
                        if (selectedHost == "local") {
                            (app as ClientApplication).providersRepository.value?.invalidate()
                            (app as ClientApplication).subscriptionsRepository.value?.invalidate()
                            modelsModel.invalidatePicker()
                        }
                    }
                }
            }
        }
        collectInto(appearanceController.state) { state, value -> state.copy(appearance = value) }
        collectInto(mediaGeneration.state) { state, value -> state.copy(mediaGeneration = value) }
        viewModelScope.launch { mediaGeneration.effects.collect { handleMediaEffect(it) } }
        viewModelScope.launch {
            combine(state, conversations.state) { value, snapshot ->
                    val id = watchedId
                    val chat = id?.let { snapshot.chats[it] }
                    MediaPublication(
                        value.hostIdentity,
                        value.connectionLive,
                        id,
                        chat?.mediaRevision ?: 0,
                        chat?.generatedMedia ?: persistentMapOf(),
                    )
                }
                .distinctUntilChanged { before, after ->
                    before.host == after.host &&
                        before.live == after.live &&
                        before.session == after.session &&
                        before.revision == after.revision &&
                        before.entries === after.entries
                }
                .collect { value ->
                    if (value.live && value.session != null)
                        mediaGeneration.ingest(value.session, value.entries)
                }
        }
        collectInto(terminal.state) { state, value -> state.copy(terminal = value) }
        viewModelScope.launch {
            terminal.effects.collect { effect ->
                if (effect is dev.fughetta.dshcompose.ui.terminal.TerminalEffect.CopyClipboard)
                    try {
                        val clipboard =
                            app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("", effect.text),
                        )
                    } catch (_: Exception) {
                        mutableState.update { it.copy(error = "terminal/clipboard-failed") }
                    }
            }
        }
        collectInto(notificationSettings.state) { state, value ->
            state.copy(notificationSettings = value)
        }
        collectInto(notificationSettings.banner) { state, value ->
            state.copy(notificationBanner = value)
        }
        viewModelScope.launch { nativePlugins.effects.collect { handleNativePluginEffect(it) } }
        collectInto(featureModules.state) { state, value ->
            state.copy(
                contributions =
                    FeatureContributions(
                        value.contributions
                            .filterIsInstance<AppModuleContribution.Menu>()
                            .map { it.entry }
                            .toPersistentList(),
                    ),
                featurePages = value.contributions.filterIsInstance<AppModuleContribution.Page>(),
                featureCatalog =
                    value.contributions.filterIsInstance<AppModuleContribution.SearchEntry>().map {
                        it.entry
                    },
                composerActions =
                    value.contributions.filterIsInstance<AppModuleContribution.ComposerAction>(),
                messageRenderers =
                    value.contributions.filterIsInstance<AppModuleContribution.MessageRenderer>(),
            )
        }
        collectInto(nativePlugins.state) { state, value -> state.copy(nativePlugins = value) }
        collectInto(pluginSeats.state) { state, value -> state.copy(pluginSeats = value) }
        mutableState.bind(
            viewModelScope,
            performanceSettings.state,
            { state, value -> state.copy(performanceSettings = value) },
        ) { value ->
            appearanceController.performance(value.lightweight, value.animations)
        }
        collectInto(engineSettings.state) { state, value -> state.copy(engineSettings = value) }
        collectInto(pluginManagement.state) { state, plugins ->
            state.copy(pluginManagement = plugins)
        }
        collectInto(phoneControls.state) { state, phone -> state.copy(phoneControls = phone) }
        viewModelScope.launch {
            combine(sessionRepository.state, state) { catalog, shell ->
                    catalog.sessions to shell.workspaces.archived
                }
                .distinctUntilChanged { a, b ->
                    a.first === b.first && (a.second === b.second || a.second == b.second)
                }
                .collect { (rows, archived) -> sessionRepository.indexSessions(rows, archived) }
        }
        viewModelScope.launch {
            var previousLabels: Set<String> = emptySet()
            var previousIndex = ReferenceLabelIndex.Empty
            combine(
                    sessionRepository.state
                        .map { it.sessions }
                        .distinctUntilChanged { before, after -> before === after },
                    pickedReferenceLabels,
                ) { sessions, picked ->
                    sessions to picked
                }
                .collectLatest { (sessions, picked) ->
                    val index =
                        withContext(Dispatchers.Default) {
                            val names =
                                (sessions.asSequence().mapNotNull { it.title } +
                                        picked.asSequence())
                                    .filter { it.isNotBlank() }
                                    .toSet()
                            if (names == previousLabels) previousIndex
                            else {
                                ReferenceLabelIndex.build(names.asSequence()).also {
                                    previousLabels = names
                                    previousIndex = it
                                }
                            }
                        }
                    mutableState.update { current ->
                        if (
                            sessionRepository.state.value.sessions === sessions &&
                                pickedReferenceLabels.value === picked
                        )
                            current.copy(referenceLabels = index)
                        else current
                    }
                }
        }
        runtime.addSink(sink)
        mutableState.update { it.copy(runtime = it.runtime.copy(logs = runtime.recentLogs(300))) }
        status(runtime.status())
    }
}

internal fun AppViewModel.observeNetwork() {
    run {
        runCatching {
            getApplication<Application>()
                .getSystemService(android.net.ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkWatch)
        }
    }
}

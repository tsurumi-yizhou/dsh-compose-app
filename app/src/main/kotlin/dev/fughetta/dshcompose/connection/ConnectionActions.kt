package dev.fughetta.dshcompose.connection

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.attachments.importShares
import dev.fughetta.dshcompose.domain.hosts.ConnectedHost
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.hosts.LinkState
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.engine.runtime.RuntimeController
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.routeNotification
import dev.fughetta.dshcompose.sessions.approvalsFor
import dev.fughetta.dshcompose.sessions.loadDrawerPreferences
import dev.fughetta.dshcompose.sessions.prepareConversationDraft
import dev.fughetta.dshcompose.sessions.questionsFor
import dev.fughetta.dshcompose.sessions.refreshSessionAttention
import dev.fughetta.dshcompose.sessions.refreshSessions
import dev.fughetta.dshcompose.sessions.repairAutoDefaultPreset
import dev.fughetta.dshcompose.sessions.scheduleInventoryRefresh
import dev.fughetta.dshcompose.sessions.updateQuestionWaits
import dev.fughetta.dshcompose.sessions.updateSessionInventory
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** How a connect attempt obtains its client. */
internal sealed interface ConnectMode {
    /** Manager-owned link, rebuilt from the registered endpoint provider. */
    data object Provider : ConnectMode

    /** Nothing can reach the host right now (for example the engine is stopped). */
    data object Offline : ConnectMode
}

private fun AppViewModel.bindScreens(
    host: String,
    epoch: Long,
    client: ConnectedHost?,
    sessionId: String?,
    ready: Boolean = false,
) = run {
    val repositories = connectionSession?.takeIf { it === client }?.repositories
    hosts.selectSession(selectedConnection, sessionId)
    uiConfig.connect(repositories?.uiConfig, host)
    connectConnectivity(client?.state?.value?.generation)
    transcriptArtifacts.connect(repositories?.files, host, host == "local")
    usage.monitor.connect(repositories?.usage, host, sessionId.takeIf { ready })
    nativePlugins.connect(repositories?.pluginUi, host, pluginLocale())
    terminal.connect(repositories?.terminals, host, sessionId)
    mediaGeneration.connect(mediaRepository.connection.value.takeIf { ready }, host)
    replyLinks.connect(repositories?.files, epoch)
    workspaces.connect(repositories?.workspaces, host)
    pluginManagement.connect(
        repositories?.plugins,
        isLocal = client != null && host == "local",
    )

    directoryPicker.connect(repositories?.directoryPicker)
    workspaceFiles.connect(repositories?.files)
    references.connect(repositories?.files)
    skills.connect(repositories?.skillsPorts)
    pluginSeats.connect(repositories?.pluginUi)
    engineSettings.connect(repositories?.engineSettings)
    permissions.connect(repositories?.permissions)
    commands.connect(sessionRepository.connection.value)
    sessionExport.connect(repositories?.sessions)
    workspaceExporter.connect(repositories?.files)
}

internal fun AppViewModel.closeConnectionScreens() = run {
    workspaceExporter.close()
    sessionExport.close()
    commands.close()
    permissions.close()
    engineSettings.close()
    pluginSeats.disconnect()
    skills.close()
    references.close()
    workspaceFiles.close()
    directoryPicker.close()

    pluginManagement.close()
    workspaces.close()
    replyLinks.close()
    mediaGeneration.close()
    terminal.close()
    nativePlugins.close()
    usage.monitor.close()
    transcriptArtifacts.close()
    uiConfig.close()
}

internal fun AppViewModel.status(value: Map<String, Any?>): Unit {
    run {
        val phase =
            when (value["phase"]) {
                "installing" -> RuntimePhase.Installing
                "installed" -> RuntimePhase.Installed
                "starting" -> RuntimePhase.Starting
                "running" -> RuntimePhase.Running
                "stopping" -> RuntimePhase.Stopping
                "error" -> RuntimePhase.Error
                else -> RuntimePhase.NotInstalled
            }
        if (phase != mutableState.value.runtime.phase) uiConfig.kick()
        mutableState.update {
            it.copy(
                runtime =
                    it.runtime.copy(
                        phase = phase,
                        error = value["error"] as? String,
                        progress = it.runtime.progress.takeIf { phase == RuntimePhase.Installing },
                    ),
            )
        }
        val url = value["baseUrl"] as? String
        val token = value["token"] as? String
        val identity =
            if (phase == RuntimePhase.Running && url != null && token != null) url to token
            else null
        if (identity == connectionIdentity) return
        connectionIdentity = identity
        hosts.observeLocal(localObserver, identity)
        if (selectedHost == "local") connectLocal()
    }
}

internal fun AppViewModel.connectLocal(): Unit {
    run {
        val actual = runtime.status()
        val url = actual["baseUrl"] as? String
        val token = actual["token"] as? String
        val identity =
            if (runtime.phase == RuntimeController.Phase.RUNNING && url != null && token != null)
                url to token
            else null
        if (identity != connectionIdentity) {
            connectionIdentity = identity
            hosts.invalidate("local")
            hosts.observeLocal(localObserver, identity)
        }
        changeConnection(
            "local",
            if (identity == null) ConnectMode.Offline else ConnectMode.Provider,
        )
    }
}

internal fun AppViewModel.connectService(id: String): Unit {
    run {
        val host = state.value.remoteHosts.firstOrNull { it.id == id } ?: return
        changeConnection(id, ConnectMode.Provider)
    }
}

internal fun AppViewModel.saveRemoteHost(action: AppAction.SaveRemoteHost): Unit {
    run {
        val editor = state.value.remoteHostEditor ?: return
        if (
            state.value.remoteHostsBusy ||
                action.name.isBlank() ||
                dev.fughetta.dshcompose.domain.hosts.hostServiceUrl(action.target.url) == null ||
                action.target.code.isBlank()
        )
            return
        val host = editor.copy(name = action.name.trim(), url = action.target.url)
        mutableState.update { it.copy(remoteHostsBusy = true, remoteHostError = null) }
        viewModelScope.launch {
            try {
                val hosts = state.value.remoteHosts.filterNot { it.id == host.id } + host
                remoteHostsRepository.saveServices(hosts, host.id, action.target.code)
                mutableState.update {
                    it.copy(
                        remoteHosts = hosts,
                        remoteHostsBusy = false,
                        remoteHostEditor = null,
                    )
                }
                connectService(host.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message =
                    when (error) {
                        is dev.fughetta.dshcompose.protocol.HostRemoteException ->
                            when (error.code) {
                                "pairing/invalid" -> R.string.compose_pair_invalid
                                "pairing/expired",
                                "pairing/locked" -> R.string.compose_pair_expired
                                "pairing/rate-limited" -> R.string.compose_pair_limited
                                "device/refused" -> R.string.compose_pair_refused
                                else -> R.string.compose_host_failed
                            }
                        is dev.fughetta.dshcompose.protocol.HostHttpException ->
                            when (error.status) {
                                401 -> R.string.compose_pair_expired
                                403 -> R.string.compose_pair_refused
                                429 -> R.string.compose_pair_limited
                                else -> R.string.compose_host_failed
                            }
                        is dev.fughetta.dshcompose.protocol.HostTransportException ->
                            R.string.compose_pair_unreachable
                        else -> R.string.compose_pair_save_failed
                    }
                mutableState.update {
                    it.copy(
                        remoteHostsBusy = false,
                        remoteHostError = AppStrings.get(getApplication(), message),
                    )
                }
            }
        }
    }
}

internal fun AppViewModel.changeConnection(
    host: String,
    mode: ConnectMode,
    workingDirectory: String? = null,
): Unit {
    run {
        if (pendingNotification?.host != host) pendingNotification = null
        if (host != "local") pendingLocalReveal = null
        hostWorkingDirectory = workingDirectory
        val hostChanged = host != selectedHost
        val previousHost = selectedHost
        if (selectedHost != "local") notificationSettings.connect(selectedHost, false, null)
        if (hostChanged) visibleSessionId = null
        client?.state?.value?.generation?.let {
            pendingPrompts.connectionLost(selectedHost, it.home)
            pendingPrompts.persist(selectedHost, it.home)
        }
        selectedHost = host
        (app as ClientApplication).remoteUpdates.select(host)
        if (mode !is ConnectMode.Offline || host == "local")
            hostSelection
                .edit()
                .putString(
                    "selected",
                    host.takeIf { it.startsWith("compose-") } ?: "local",
                )
                .apply()
        notificationSettings.visibility(foreground, selectedHost, visibleSessionId)
        connectionNumber++
        val number = connectionNumber
        draftCreating = false
        sessionsModel.disconnect()
        drawerUi.disconnect()
        attentionTimer?.cancel()
        pendingSince.clear()
        attentionSessions = emptyList()
        mutableState.update {
            it.copy(
                linkState = LinkState.Offline,
                sessionAttention =
                    it.sessionAttention.copy(
                        pending = persistentMapOf(),
                        unread = persistentSetOf(),
                    ),
            )
        }
        val needsDrawerLoad = hostChanged || !drawerUi.state.value.loaded
        if (hostChanged) {
            composer.resetTransient()
            watchedId = null
            val retained = pendingPrompts.retainedFileIds()
            val discarded = composerAttachments.clear().filterNot { it.id in retained }
            viewModelScope.launch { composerFiles.release(discarded) }
            conversations.reset()
            mutableState.update {
                it.copy(
                    hostIdentity = host,
                    openSessionId = null,
                    selectedSessionId = null,
                    pluginSeatSession = null,
                    sessionArtifacts = null,
                    error = null,
                )
            }
        }
        conversationModel.resetMessage()
        sendOwners.clear()
        imageExports.dismiss()
        replyImageLoader.memoryCache?.clear()
        mutableState.update {
            it.copy(imageGeneration = connectionNumber, linkState = LinkState.Offline)
        }
        hosts.disconnectSelection(selectedConnection, loading = mode !is ConnectMode.Offline)
        bindScreens(host, number, null, watchedId)
        pickedReferenceLabels.value = persistentSetOf()

        conversationModel.clear()
        retiredSessions.clear()
        pickerSession = null
        requestResponses.clear()
        requestsMonitor?.cancel()
        eventsMonitor?.cancel()
        questionWaits.values.forEach { it.cancel() }
        questionWaits.clear()
        questionDrafts.clear()
        questionRemaining.clear()
        connecting?.cancel()
        connectionMonitor?.cancel()
        conversations.reconnecting()
        mutableState.update {
            it.copy(
                referenceLabels = ReferenceLabelIndex.Empty,
                deletedSessionId = null,
            )
        }
        if (needsDrawerLoad) loadDrawerPreferences(host)
        if (watchedId == null) updateChat(DRAFT_SESSION_ID) { it.copy(syncing = false) }
        if (mode is ConnectMode.Offline) {
            // No way to reconnect this host (engine stopped): drop the cached link instead of
            // leaving its mux retrying in the background forever.
            hosts.invalidate(host)
            return
        }
        connecting = viewModelScope.launch {
            try {
                val session =
                    hosts.open(selectedConnection, host) { connectionNumber == number }
                        ?: return@launch
                val candidate = session
                session.repositories?.sessions?.let {
                    drawerUi.connect(host, it)
                    sessionsModel.connect(
                        it,
                        candidate.state.value.state == ConnectionState.Connected,
                    )
                }
                bindScreens(host, number, candidate, watchedId)
                if (host == "local") repairAutoDefaultPreset()
                nativePlugins.session(watchedId)
                connectionMonitor =
                    session.scope.launch {
                        var observedGeneration = selectedConnection.state.value.generation
                        selectedConnection.state.collect { link ->
                            if (client === candidate) {
                                mutableState.update { it.copy(linkState = hosts.state(host).value) }
                                sessionsModel.connected(link.state == ConnectionState.Connected)
                                if (link.state == ConnectionState.Connected) {
                                    link.generation?.home?.let { rememberHome(host, it) }
                                    // Every connected host has a registered endpoint provider
                                    // (local engine, paired compose service), so each keeps its
                                    // mirror
                                    // fresh in the background.
                                    dev.fughetta.dshcompose.sync.SessionSyncWork.ensurePeriodic(
                                        app,
                                        host,
                                    )
                                }
                                if (
                                    link.state != ConnectionState.Connected ||
                                        link.generation != observedGeneration
                                )
                                    sessionsModel.resetDeletionSupport()
                                refreshSessionAttention()
                                if (
                                    link.state == ConnectionState.Connected &&
                                        link.generation != observedGeneration
                                ) {
                                    observedGeneration = link.generation
                                    session.repositories?.sessions?.let {
                                        drawerUi.connect(host, it)
                                        sessionsModel.connect(it, true)
                                    }
                                    scheduleInventoryRefresh(candidate)
                                }
                                if (link.state == ConnectionState.Connected)
                                    watchedId?.let { pendingPrompts.resume() }
                                if (link.state != ConnectionState.Connected) {
                                    observedGeneration?.let {
                                        pendingPrompts.connectionLost(host, it.home)
                                        pendingPrompts.persist(
                                            host,
                                            it.home,
                                        )
                                    }
                                    conversationModel.resetMessage()
                                }
                                bindScreens(
                                    host,
                                    number,
                                    candidate.takeIf { link.state == ConnectionState.Connected },
                                    watchedId,
                                    link.state == ConnectionState.Connected,
                                )
                                updateQuestionWaits(session.repositories?.sessions)
                                if (host != "local")
                                    notificationSettings.connect(
                                        host,
                                        false,
                                        connectionSession?.repositories?.notifications,
                                    )
                                refreshUsageAccount()
                                mediaGeneration.session(watchedId)
                                nativePlugins.session(watchedId)
                            }
                            if (client === candidate && link.state != ConnectionState.Connected) {

                                conversations.disconnected()
                            }
                        }
                    }
                requestsMonitor =
                    session.scope.launch {
                        session.requests.collect { requests ->
                            if (client !== candidate) return@collect
                            requests.error?.let { failure ->
                                mutableState.update { it.copy(error = failure) }
                            }
                            refreshSessionAttention()
                            updateQuestionWaits(session.repositories?.sessions)
                            val transcript = activeConversation
                            val id = watchedId
                            if (transcript != null && id != null)
                                transcript.pendingApprovals(
                                    requests.approvals.values
                                        .filter { it.sessionId == id }
                                        .mapNotNull { it.callId }
                                        .toSet(),
                                )
                            conversations.updateAll { sessionId, chat ->
                                chat.copy(
                                    approvals = approvalsFor(sessionId),
                                    questions = questionsFor(sessionId),
                                )
                            }
                        }
                    }
                eventsMonitor =
                    session.scope.launch {
                        candidate.hostEvents.collect { event ->
                            if (client === candidate && host != "local")
                                notificationSettings.onHostEvent(host, event)
                            if (client === candidate && event.text("type") == "cancel")
                                event.text("eventId")?.let { questionDrafts.remove(it) }
                            if (client === candidate && event.text("type") == "emit") {
                                val name = event.text("event")
                                pluginSeats.hostEvent(name)
                                engineSettings.hostEvent(name)
                                pluginManagement.hostEvent(event)
                                uiConfig.hostEvent(name)
                                updateSessionInventory(event)
                                if (name == "plugin-manager/changed") {
                                    nativePlugins.refresh()
                                    mediaGeneration.refreshCatalog()
                                }
                                if (
                                    name == "credentials/reference-updated" ||
                                        name == "credentials/record-updated" ||
                                        name == "settings/document-updated"
                                )
                                    mediaGeneration.refreshCatalog()
                                val namespace =
                                    event.arrayItems("args")?.firstOrNull()?.primitiveContent
                                if (
                                    name in
                                        setOf(
                                            "credentials/reference-updated",
                                            "llm/adapters-updated",
                                        ) ||
                                        name == "settings/document-updated" &&
                                            namespace in setOf("llm-pi-ai", "agent-default-model")
                                ) {
                                    modelsModel.invalidatePicker()
                                }
                                if (name in setOf("commands/change", "plugin-manager/changed"))
                                    skills.invalidate()
                                if (
                                    name in
                                        setOf(
                                            "commands/change",
                                            "agent-preset/selected",
                                            "plugin-manager/changed",
                                        )
                                )
                                    commands.invalidate()
                                if (
                                    name == "permission-presets/catalog-changed" ||
                                        name == "settings/document-updated" &&
                                            namespace == "permission"
                                )
                                    permissions.invalidate()
                            }
                        }
                    }
                refreshSessions(candidate)
                if (client !== candidate || connectionNumber != number) return@launch
                val notification = pendingNotification?.takeIf { it.host == host }
                if (notification != null) {
                    pendingNotification = null
                    routeNotification(notification, candidate)
                } else if (host == "local")
                    pendingLocalReveal?.let { id ->
                        pendingLocalReveal = null
                        mutableState.update {
                            it.copy(
                                openSessionId = id,
                                openSessionRevision = it.openSessionRevision + 1,
                            )
                        }
                        openSession(id)
                    } ?: watchedId?.let { openSession(it) }
                else watchedId?.let { openSession(it) }
                if (notification == null && watchedId == null && pendingComposeText == null) {
                    settings
                        .restoreSession(
                            host,
                            sessionRepository.state.value.sessions.mapTo(hashSetOf()) {
                                it.sessionId
                            },
                        )
                        ?.let { id ->
                            mutableState.update {
                                it.copy(
                                    openSessionId = id,
                                    openSessionRevision = it.openSessionRevision + 1,
                                )
                            }
                            openSession(id)
                        }
                }
                pendingComposeText?.let { prepareConversationDraft(it) }
                importShares()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connectionNumber == number) {
                    if (host.startsWith("compose-")) {
                        connectLocal()
                        mutableState.update {
                            it.copy(
                                error =
                                    AppStrings.get(
                                        getApplication(),
                                        R.string.compose_host_failed,
                                    ),
                            )
                        }
                    } else mutableState.update { it.copy(error = error.message) }
                }
            }
        }
    }
}

internal fun AppViewModel.connectConnectivity(generation: Any?): Unit {
    run {
        val kind =
            when {
                selectedHost == "local" ->
                    dev.fughetta.dshcompose.ui.connectivity.ConnectivityKind.Local
                else -> dev.fughetta.dshcompose.ui.connectivity.ConnectivityKind.Service
            }
        mutableState.update {
            it.copy(
                connectivity =
                    dev.fughetta.dshcompose.ui.connectivity.ConnectivityState(
                        selectedHost,
                        selectedHost,
                        kind,
                        generation != null,
                    ),
            )
        }
    }
}

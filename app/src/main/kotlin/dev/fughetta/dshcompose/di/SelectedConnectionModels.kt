package dev.fughetta.dshcompose.di

import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.AppViewModel
import dev.fughetta.dshcompose.ClientApplication
import dev.fughetta.dshcompose.data.link.SelectedConnection
import dev.fughetta.dshcompose.onLocalConnectionChanged
import dev.fughetta.dshcompose.onLocalHostEvent
import dev.fughetta.dshcompose.onLocalRunFinished
import dev.fughetta.dshcompose.onLocalRunStarted
import dev.fughetta.dshcompose.onLocalSummaries
import dev.fughetta.dshcompose.phoneFailure

internal fun AppViewModel.createSelectedConnection():
    dev.fughetta.dshcompose.domain.hosts.HostSelection =
    (app as ClientApplication).dataSources.let { application ->
        val selected =
            SelectedConnection(
                viewModelScope,
                application.messageAttachmentsRepository,
                pendingPrompts as dev.fughetta.dshcompose.data.messages.EngineMessagesRepository,
                application.sessionModelsRepository,
                application.providersRepository,
                application.subscriptionsRepository,
                application.agentPresetsRepository,
                application.agentTeamsRepository,
                application.sessionsRepository,
                application.settingsRepository,
                application.schedulesRepository,
                application.goalsRepository,
                application.jobsRepository,
                application.mediaRepository,
            )
        application.hostRepository.registerSelection(
            selected,
            pendingPrompts as dev.fughetta.dshcompose.data.messages.EngineMessagesRepository,
            application.sessionsRepository,
        )
    }

internal fun AppViewModel.createLocalSessionObserver():
    dev.fughetta.dshcompose.domain.hosts.LocalHostObservation =
    (app as ClientApplication)
        .dataSources
        .hostRepository
        .registerObserver(
            dev.fughetta.dshcompose.data.link.LocalSessionObserver(
                viewModelScope,
                ::onLocalConnectionChanged,
                ::onLocalRunStarted,
                ::onLocalRunFinished,
                ::phoneFailure,
                ::onLocalHostEvent,
                ::onLocalSummaries,
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "native",
                ::pluginLocale,
            ),
        )

internal fun AppViewModel.createPendingPrompts():
    dev.fughetta.dshcompose.domain.messages.MessagesRepository =
    dev.fughetta.dshcompose.data.messages.EngineMessagesRepository(viewModelScope).apply {
        configureDelivery(
            { selectedConnection },
            (app as ClientApplication).dataSources.pendingPromptOutbox,
        )
    }

internal fun android.content.Context.createLocalSessions(
    scope: kotlinx.coroutines.CoroutineScope,
    appVersion: String,
    locale: () -> String,
    changed: () -> Unit,
    started: (String) -> Unit,
    finished: suspend (String) -> Unit,
    failed: (String) -> Unit,
): dev.fughetta.dshcompose.domain.hosts.LocalHostObservation =
    (applicationContext as ClientApplication)
        .dataSources
        .hostRepository
        .registerObserver(
            dev.fughetta.dshcompose.data.link.LocalSessionObserver(
                scope,
                changed,
                started,
                finished,
                failed,
                appVersion = appVersion,
                locale = locale,
            ),
        )

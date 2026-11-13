package dev.fughetta.dshcompose.di

import dev.fughetta.dshcompose.ClientApplication
import kotlinx.coroutines.CoroutineScope

/**
 * Process-scoped construction; consumers receive domain repositories and their connection flows.
 */
internal class ApplicationRepositories(
    private val app: ClientApplication,
    private val applicationScope: CoroutineScope,
) {
    val mediaRepository by lazy { dev.fughetta.dshcompose.data.media.EngineMediaRepository(app) }

    val messageAttachmentsRepository by lazy {
        dev.fughetta.dshcompose.data.messages.AttachmentConnections()
    }

    val sessionModelsRepository by lazy {
        dev.fughetta.dshcompose.data.providers.EngineSessionModelsRepository()
    }

    val providersRepository by lazy {
        dev.fughetta.dshcompose.data.providers.EngineProvidersRepository(
            onChanged = { sessionModelsRepository.connection.value?.invalidate() },
        )
    }

    val subscriptionsRepository by lazy {
        dev.fughetta.dshcompose.data.providers.EngineSubscriptionsRepository(
            onChanged = {
                sessionModelsRepository.connection.value?.invalidate()
                providersRepository.connection.value?.invalidate()
            },
        )
    }

    val agentPresetsRepository by lazy {
        dev.fughetta.dshcompose.data.presets.EngineAgentPresetsRepository()
    }

    val agentTeamsRepository by lazy {
        dev.fughetta.dshcompose.data.sessions.EngineAgentTeamsRepository()
    }

    val sessionsRepository by lazy {
        dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository(
            sessionMirror,
            applicationScope,
        )
    }

    /** Local-first mirror; connections write it so a later phase can read the catalog offline. */
    val sessionMirror by lazy { dev.fughetta.dshcompose.data.sync.createSessionMirror(app) }

    /**
     * Single owner of host links; a reconnect inside the idle window reuses the same client. The
     * local engine endpoint is registered here (not in the view model) so a background worker can
     * open it after a cold start.
     */
    val hostLinks by lazy {
        dev.fughetta.dshcompose.data.link.HostConnections(applicationScope).apply {
            provideRemoteHosts(remoteHostsRepository::load, remoteHostsRepository::credential)
            provide(
                { hostId ->
                    if (hostId != "local") null
                    else
                        dev.fughetta.dshcompose.data.link.LocalEngineEndpoint(
                            identity = {
                                val status =
                                    dev.fughetta.dshcompose.runtime.AppRuntime.get(app).status()
                                val url = status["baseUrl"] as? String
                                val token = status["token"] as? String
                                if (url == null || token == null) null else url to token
                            },
                        )::open
                },
            )
        }
    }

    val hostRepository by lazy {
        dev.fughetta.dshcompose.data.hosts.EngineHostsRepository(
            hostLinks,
            readServices = remoteHostsRepository::load,
            writeServices = remoteHostsRepository::save,
            mirror = sessionMirror,
        )
    }

    /** Durable copy of prompts a dropped link left uncertain, restored on the next connection. */
    val pendingPromptOutbox by lazy {
        dev.fughetta.dshcompose.data.sync.createPendingPromptOutbox(app)
    }

    val jobsRepository by lazy {
        dev.fughetta.dshcompose.data.jobs.createJobsRepository(app, applicationScope)
    }

    val goalsRepository by lazy { dev.fughetta.dshcompose.data.goals.EngineGoalsRepository() }

    val schedulesRepository by lazy {
        dev.fughetta.dshcompose.data.schedules.EngineSchedulesRepository()
    }

    val draftsRepository by lazy {
        dev.fughetta.dshcompose.data.drafts.createDraftsRepository(app, applicationScope)
    }

    val remoteHostsRepository by lazy {
        dev.fughetta.dshcompose.data.hosts.createRemoteHostStore(app)
    }

    val settingsRepository by lazy { dev.fughetta.dshcompose.di.deviceSettings(app) }
}

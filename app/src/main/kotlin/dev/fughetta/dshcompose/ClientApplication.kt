package dev.fughetta.dshcompose

import android.app.Application
import android.util.Log
import dev.fughetta.dshcompose.di.localPreferences
import kotlinx.coroutines.*

class ClientApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val dataSources by lazy {
        dev.fughetta.dshcompose.di.ApplicationRepositories(this, applicationScope)
    }

    internal val remoteUpdates by lazy {
        dev.fughetta.dshcompose.update.RemoteUpdates(
            this,
            dataSources.remoteHostsRepository,
            applicationScope,
        )
    }

    val mediaRepository: dev.fughetta.dshcompose.domain.media.MediaRepository
        get() = dataSources.mediaRepository

    val messageAttachmentsRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository?,
        >
        get() = dataSources.messageAttachmentsRepository.connection

    val sessionModelsRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.providers.SessionModelsRepository?,
        >
        get() = dataSources.sessionModelsRepository.connection

    val providersRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.providers.ProvidersRepository?,
        >
        get() = dataSources.providersRepository.connection

    val subscriptionsRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.providers.SubscriptionsRepository?,
        >
        get() = dataSources.subscriptionsRepository.connection

    val agentPresetsRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.presets.PresetsRepository?,
        >
        get() = dataSources.agentPresetsRepository.connection

    val agentTeamsRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.sessions.AgentTeamsRepository?,
        >
        get() = dataSources.agentTeamsRepository.connection

    val sessionsRepository: dev.fughetta.dshcompose.domain.sessions.SessionsRepository
        get() = dataSources.sessionsRepository

    val jobsRepository: dev.fughetta.dshcompose.domain.jobs.JobsRepository
        get() = dataSources.jobsRepository

    val goalsRepository: dev.fughetta.dshcompose.domain.goals.GoalsRepository
        get() = dataSources.goalsRepository

    val schedulesRepository:
        kotlinx.coroutines.flow.StateFlow<
            dev.fughetta.dshcompose.domain.schedules.SchedulesRepository?,
        >
        get() = dataSources.schedulesRepository.connection

    val draftsRepository: dev.fughetta.dshcompose.domain.drafts.DraftsRepository
        get() = dataSources.draftsRepository

    val settingsRepository: dev.fughetta.dshcompose.domain.settings.SettingsRepository
        get() = dataSources.settingsRepository

    val hostsRepository: dev.fughetta.dshcompose.domain.hosts.HostsRepository
        get() = dataSources.hostRepository

    override fun onCreate() {
        super.onCreate()
        // Register paired compose services before WorkManager or a UI can request one.
        hostsRepository
        remoteUpdates
        applicationScope.launch {
            val storage = localPreferences("storage")
            if (storage.getBoolean("retiredPreferencesRemoved", false)) return@launch
            // A reset was chosen for the retired app stores. Leave library-owned stores alone.
            val retired =
                listOf(
                    "FlutterSharedPreferences",
                    "dsh_ui",
                    "ssh_hosts",
                    "chat_drafts",
                    "dsh_bot_notice_cursors",
                    "dsh_performance_device",
                    "voice_recognition_health",
                    "dsh_phone",
                    "phone_controls_ui",
                    "phone-privileged-keep",
                    "dsh_phone_overlay",
                    "background_net",
                    "dsh_runtime",
                    "dsh_notify",
                    "dsh_live",
                    "dsh_notification_preferences",
                    "dsh_performance_preferences",
                    "media-generation",
                    "launch_theme",
                    "ui_config",
                    "share-poster",
                    "dsh_native_onboarding",
                    "dsh_native_updates",
                    "launcher_icon",
                    "community",
                    "star-prompt",
                    "native_plugins",
                    "dsh_appearance",
                    "usage_preferences",
                )
            var removed = true
            for (name in retired) {
                if (!deleteSharedPreferences(name)) removed = false
            }
            if (getDatabasePath("media-works.db").exists() && !deleteDatabase("media-works.db"))
                removed = false
            val paired = java.io.File(filesDir, "phone-adb/paired")
            if (paired.exists() && !paired.delete()) removed = false
            if (removed) storage.edit().putBoolean("retiredPreferencesRemoved", true).apply()
            else Log.e("AppStorage", "preferences/reset-failed")
        }
    }
}

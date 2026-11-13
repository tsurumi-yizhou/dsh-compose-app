package dev.fughetta.dshcompose.runtime

import android.content.Context
import android.content.Intent
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.engine.runtime.*
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.performance.PerformancePolicy
import dev.fughetta.dshcompose.phone.PhoneControl
import dev.fughetta.dshcompose.phone.PhoneGuest
import java.io.File

/** One engine owner shared by activities, receivers and the foreground service. */
object AppRuntime {
    @Volatile private var instance: RuntimeController? = null

    fun get(context: Context): RuntimeController =
        instance
            ?: synchronized(this) {
                instance
                    ?: context.applicationContext.let { app ->
                        RuntimeController(app, AndroidRuntimeHost(app)).also { instance = it }
                    }
            }
}

private class AndroidRuntimeHost(private val app: Context) : RuntimeHost {
    override val preferences = app.localPreferences("dsh_runtime")
    override val serviceRunning
        get() = EngineService.isRunning

    override val listSeparator
        get() = if (AppStrings.isEnglish(app)) ", " else "、"

    override fun startService() {
        app.startForegroundService(
            Intent(app, EngineService::class.java).setAction(EngineService.ACTION_START),
        )
    }

    override fun requestStop() {
        app.startService(
            Intent(app, EngineService::class.java).setAction(EngineService.ACTION_STOP),
        )
    }

    override fun stopService() = EngineService.stopSelfIfRunning(app)

    override fun updateNotification() = EngineService.updateNotification(app)

    override fun cancelStoppedAlert() = EngineService.cancelStoppedAlert(app)

    override fun postStoppedAlert() = EngineService.postStoppedAlert(app)

    override fun storageBinds(requested: Boolean, rootfs: File): List<String> {
        val binds =
            if (requested && PhoneStorage.isGranted(app)) PhoneStorage.binds() else emptyList()
        PhoneStorage.syncShortcut(rootfs, binds.isNotEmpty())
        return binds
    }

    override fun prepareCapabilities(
        rootfs: File,
        log: (String) -> Unit,
    ): RuntimeCapabilities {
        val phoneEnabled = PhoneControl.get(app).prepareLaunch(rootfs, log)
        return if (phoneEnabled)
            RuntimeCapabilities(listOf(PhoneGuest.PATCH), PhoneGuest.AGENTS_SECTION)
        else RuntimeCapabilities()
    }

    override fun applyEnvironment(env: MutableMap<String, String>, manifest: RuntimeManifest) {
        PerformancePolicy.applyEngineEnvironment(app, env, manifest.launchArgv, manifest.launchEnv)
    }

    override fun capabilitiesLaunched(
        requested: RuntimeCapabilities,
        mountedPatches: List<String>,
    ) {
        if (PhoneGuest.PATCH in requested.patches)
            PhoneControl.get(app).launched(PhoneGuest.PATCH in mountedPatches)
    }

    override fun engineReady() = PhoneControl.get(app).onEngineReady()

    override fun engineStopped() = PhoneControl.get(app).onEngineStopped()

    override fun text(message: RuntimeMessage, vararg args: Any): String =
        AppStrings.get(
            app,
            when (message) {
                RuntimeMessage.PACKAGE_CHECKSUM_FAILED -> R.string.service_package_checksum_failed
                RuntimeMessage.STORAGE_INSUFFICIENT -> R.string.service_storage_insufficient
                RuntimeMessage.STOP_ENGINE_FIRST -> R.string.safe_stop_engine_first
                RuntimeMessage.NO_GOOD_CONFIG -> R.string.safe_no_good_config
                RuntimeMessage.CONFIG_MISSING -> R.string.safe_config_missing
                RuntimeMessage.CONFIG_BUSY -> R.string.safe_config_busy
                RuntimeMessage.PATCH_HAZARD -> R.string.safe_patch_hazard
                RuntimeMessage.ENV_HAZARD -> R.string.safe_env_hazard
                RuntimeMessage.NORMAL_RECOVERED -> R.string.safe_normal_recovered
                RuntimeMessage.PROBATION_FAILED -> R.string.safe_probation_failed
                RuntimeMessage.AUTO_STARTED -> R.string.safe_auto_started
            },
            *args,
        )
}

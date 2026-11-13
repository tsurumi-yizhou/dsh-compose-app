package dev.fughetta.dshcompose.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.performance.DeviceProfile
import dev.fughetta.dshcompose.engine.runtime.*
import dev.fughetta.dshcompose.performance.DevicePerformance
import dev.fughetta.dshcompose.performance.PerformancePolicy
import kotlin.concurrent.thread

/** 「打开 App 时自动启动」 as soon as the activity is created, beside the first frame. */
object LaunchStart {
    /** The phone controls page's store and its storage switch (phoneui/PhoneControlsViewModel). */
    private const val PHONE_PREFS = "phone_controls_ui"
    private const val K_PHONE_STORAGE = "phoneStorage"

    @Volatile private var tried = false

    /**
     * The setting is on, the runtime is installed and idle, and the failure card is not waiting for
     * the user (two failures in a row with automatic safe mode off).
     */
    fun shouldStart(
        autoStart: Boolean,
        phase: String?,
        strikes: Int,
    ): Boolean = autoStart && phase == RuntimeController.Phase.INSTALLED.wire && strikes < 2

    /**
     * Once per process: a recreated activity, or one reopened later, leaves the engine to the user.
     */
    fun onActivityCreate(context: Context) {
        if (tried) return
        tried = true
        val app = context.applicationContext
        // The first device probe includes sysfs. Never run it on the UI thread.
        thread(name = "dsh-launch-policy", isDaemon = true) {
            val profile = runCatching { DevicePerformance.read(app) }.getOrDefault(DeviceProfile())
            Handler(Looper.getMainLooper()).post { startIfAllowed(app, profile) }
        }
    }

    private fun startIfAllowed(context: Context, profile: DeviceProfile) {
        try {
            val prefs = context.localPreferences(PHONE_PREFS)
            val controller = AppRuntime.get(context)
            val status = controller.status()
            val start =
                shouldStart(
                    autoStart = PerformancePolicy.autoStart(context, profile),
                    phase = status["phase"] as? String,
                    strikes = (status["strikes"] as? Number)?.toInt() ?: 0,
                )
            if (!start) return
            // The engine page's own start: no env, the current mode.
            controller.start(
                emptyMap(),
                phoneStorage = prefs.getBoolean(K_PHONE_STORAGE, false),
            )
        } catch (_: Exception) {
            // The engine page still offers a manual start.
        }
    }
}

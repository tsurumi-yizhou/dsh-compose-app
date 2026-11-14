package dev.fughetta.dshcompose.performance

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.fughetta.dshcompose.domain.performance.DeviceProfile
import dev.fughetta.dshcompose.domain.performance.PerformanceRules
import dev.fughetta.dshcompose.domain.performance.ThermalPolicy
import dev.fughetta.dshcompose.phoneui.NativePerformancePreferences

/** Launch-side adapter shared by the activity shortcut and every engine launch mode. */
object PerformancePolicy {
    /**
     * Facts were read on a worker. Re-read preferences at the decision, never save a derived
     * default.
     */
    fun autoStart(context: Context, profile: DeviceProfile): Boolean {
        val preferences = NativePerformancePreferences.read(context)
        return PerformanceRules.autoStart(
            profile.tier,
            preferences.mode,
            preferences.autoStartLocal,
            hot(context),
        )
    }

    /**
     * Mutates only the two budget entries; manifest flags and explicit launch values remain intact.
     */
    fun applyEngineEnvironment(
        context: Context,
        env: MutableMap<String, String>,
        argv: List<String>,
        launchEnv: Map<String, String>,
    ) {
        val p = DevicePerformance.read(context)
        val light =
            PerformanceRules.lightweight(
                p.tier,
                NativePerformancePreferences.read(context).mode,
                hot(context),
            )
        env.putAll(
            PerformanceRules.engineEnvironment(
                p,
                light,
                env,
                PerformanceRules.manifestEnvironment(argv, launchEnv),
            ),
        )
    }

    private fun hot(context: Context): Boolean =
        ThermalRuntime.lightweight
            ?: if (Build.VERSION.SDK_INT < 29) false
            else
                runCatching {
                        val manager =
                            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        (manager?.currentThermalStatus ?: 0) >= ThermalPolicy.MODERATE
                    }
                    .getOrDefault(false)
}

/**
 * Shared only within this process. The listener's recovery hysteresis applies to later launches
 * too.
 */
object ThermalRuntime {
    @Volatile var lightweight: Boolean? = null
}

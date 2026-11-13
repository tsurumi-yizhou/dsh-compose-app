package dev.fughetta.dshcompose.phoneui

import android.content.Context
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.settings.PreferenceGroup

data class NativePerformancePreferences(
    val mode: String = "auto",
    val refreshRate: String = "auto",
    val blur: Boolean? = null,
    val animations: Boolean? = null,
    val fluid: Boolean? = null,
    val autoStartLocal: Boolean? = null,
) {
    companion object {
        fun file(context: Context): PreferenceGroup =
            context.localPreferences("dsh_performance_preferences")

        fun read(context: Context): NativePerformancePreferences {
            val p = file(context)
            fun optional(key: String) = if (p.contains(key)) p.getBoolean(key, false) else null
            return NativePerformancePreferences(
                p.getString("mode", "auto") ?: "auto",
                p.getString("refreshRate", "auto") ?: "auto",
                optional("blur"),
                optional("animations"),
                optional("fluid"),
                optional("autoStartLocal"),
            )
        }
    }
}

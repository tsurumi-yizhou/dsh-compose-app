package dev.fughetta.dshcompose.di

import android.content.Context
import dev.fughetta.dshcompose.data.settings.AppSettingsRepository
import dev.fughetta.dshcompose.data.settings.createSettingsRepository
import dev.fughetta.dshcompose.domain.settings.PreferenceGroup
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import kotlinx.coroutines.*

private object DeviceSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: AppSettingsRepository? = null

    @Synchronized
    fun get(context: Context): AppSettingsRepository =
        repository ?: createSettingsRepository(context, scope).also { repository = it }
}

internal fun deviceSettings(context: Context): AppSettingsRepository = DeviceSettings.get(context)

fun Context.localPreferences(name: String): PreferenceGroup {
    val settings: SettingsRepository = deviceSettings(this)
    return settings.localPreferences(name)
}

package dev.fughetta.dshcompose.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.ui.notify.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Notification preferences and the settings-page actions. It owns the persisted flags and the
 * permission request id; system status fields (channels, live activity, Now Bar) are written by the
 * notification owner into the same [mutable] flow.
 */
class NotificationSettingsViewModel(
    context: Context,
    parent: CoroutineScope? = null,
    private val onEffect: (NotificationSettingsEffect) -> Unit,
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private val app = context.applicationContext
    private val prefs = app.localPreferences("dsh_notification_preferences")
    private val closed
        get() = !scope.isActive

    internal val mutable = MutableStateFlow(preferences())
    val state: StateFlow<NotificationSettingsState> = mutable.asStateFlow()

    fun action(action: NotificationSettingsAction) {
        if (closed) return
        when (action) {
            NotificationSettingsAction.Refresh -> onEffect(NotificationSettingsEffect.Refresh)
            is NotificationSettingsAction.Preference -> save(action.key, action.enabled)
            is NotificationSettingsAction.OpenSystem ->
                onEffect(NotificationSettingsEffect.OpenSystem(action.channel))
            NotificationSettingsAction.OpenLiveSystem ->
                onEffect(NotificationSettingsEffect.OpenLiveSystem)
            NotificationSettingsAction.OpenNowBarSystem ->
                onEffect(NotificationSettingsEffect.OpenNowBarSystem)
            NotificationSettingsAction.EnableNowBar ->
                onEffect(NotificationSettingsEffect.EnableNowBar)
            NotificationSettingsAction.RequestPermission -> requestPermission()
            is NotificationSettingsAction.PermissionResult ->
                onEffect(NotificationSettingsEffect.Refresh)
        }
    }

    fun claimPermission(id: String): Boolean {
        if (!scope.isActive || mutable.value.permissionRequest != id) return false
        mutable.update { it.copy(permissionRequest = null) }
        return true
    }

    private fun requestPermission() {
        val needsPermission =
            Build.VERSION.SDK_INT >= 33 &&
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        if (needsPermission)
            mutable.update { it.copy(permissionRequest = UUID.randomUUID().toString()) }
        else onEffect(NotificationSettingsEffect.OpenSystem(null))
    }

    private fun save(key: NotificationPreference, enabled: Boolean) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            val saved =
                try {
                    withContext(Dispatchers.IO) {
                        prefs.edit().putBoolean(key.name, enabled).commit()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            val p = preferences()
            mutable.update {
                it.copy(
                    enabled = p.enabled,
                    done = p.done,
                    failed = p.failed,
                    approval = p.approval,
                    question = p.question,
                    live = p.live,
                )
            }
            try {
                if (!saved) onEffect(NotificationSettingsEffect.Error("notify/save"))
                if (key == NotificationPreference.Enabled && !p.enabled)
                    onEffect(NotificationSettingsEffect.Disabled)
                if (
                    key in
                        setOf(
                            NotificationPreference.Live,
                            NotificationPreference.Enabled,
                            NotificationPreference.Done,
                            NotificationPreference.Failed,
                        )
                )
                    onEffect(NotificationSettingsEffect.ChannelsChanged)
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun preferences(): NotificationSettingsState =
        NotificationSettingsState(
            prefs.getBoolean("Enabled", true),
            prefs.getBoolean("Done", true),
            prefs.getBoolean("Failed", true),
            prefs.getBoolean("Approval", true),
            prefs.getBoolean("Question", true),
            prefs.getBoolean("Live", true),
        )
}

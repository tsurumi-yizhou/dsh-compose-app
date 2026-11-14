package dev.fughetta.dshcompose.phone.a11y.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.feature.accessibility.R
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.PhoneRegistry
import dev.fughetta.dshcompose.phone.SerialWorker
import dev.fughetta.dshcompose.phone.a11y.A11yKeeper
import dev.fughetta.dshcompose.phone.a11y.PhoneA11yService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A host-owned privileged channel; the feature owns the grant UI and restoration behavior. */
interface AccessibilityGrant {
    fun path(): String

    fun grant(mayStart: () -> Boolean): Map<String, Any?>
}

class AccessibilitySettingsViewModel(
    context: Context,
    private val worker: SerialWorker,
    private val grant: AccessibilityGrant,
) : ViewModel() {
    private val app = context.applicationContext
    private val mutable = MutableStateFlow(AccessibilitySettingsState())
    val state = mutable.asStateFlow()
    @Volatile private var active = true
    private var awaitingSettings = false

    fun refresh() {
        viewModelScope.launch {
            val enabled = PhoneA11yService.isEnabled(app)
            val granted = A11yKeeper.granted(app)
            val path = withContext(Dispatchers.IO) { grant.path() }
            mutable.update {
                it.copy(
                    enabled = enabled,
                    keeperGranted = granted,
                    keeperCommand =
                        "adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS",
                    grantPath = path,
                    accessibilityAttemptFailed =
                        !enabled && (awaitingSettings || it.accessibilityAttemptFailed),
                )
            }
            awaitingSettings = false
        }
    }

    fun openAccessibilitySettings() {
        awaitingSettings = true
        val component = PhoneA11yService.component(app).flattenToString()
        open(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(
                    ":settings:show_fragment_args",
                    Bundle().apply {
                        putString(":settings:fragment_args_key", component)
                    },
                ),
        )
    }

    fun openAppDetails() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))

    fun openOverlayPermission() =
        open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))

    private fun packageUri() = Uri.parse("package:${app.packageName}")

    private fun open(intent: Intent) {
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { error -> mutable.update { it.copy(error = error.message) } }
    }

    fun grantRestorePermission() = mutate {
        val result = grant.grant { active }
        if (result["ok"] == true) A11yKeeper.check(app)
        else mutable.update { it.copy(error = result["reason"] as? String) }
    }

    fun readScreen() = mutate {
        val driver = PhoneRegistry.mainDriver
        if (driver == null) {
            mutable.update { it.copy(error = AppStrings.get(app, R.string.phone_a11y_unavailable)) }
            return@mutate
        }
        when (
            val result =
                worker.run(8_000, 15_000) {
                    val availability = driver.availability()
                    check(availability.ok) {
                        availability.userReason ?: availability.reason.orEmpty()
                    }
                    driver.observe(detail = false, image = false).text
                }
        ) {
            is SerialWorker.Outcome.Done -> mutable.update { it.copy(screenText = result.value) }
            is SerialWorker.Outcome.Failed ->
                mutable.update { it.copy(error = result.error.message) }
            else ->
                mutable.update { it.copy(error = AppStrings.get(app, R.string.phone_read_timeout)) }
        }
    }

    fun closeScreen() = mutable.update { it.copy(screenText = null) }

    private fun mutate(action: () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutable.update { it.copy(error = error.message) }
            } finally {
                mutable.update { it.copy(busy = false) }
                if (active) refresh()
            }
        }
    }

    override fun onCleared() {
        active = false
    }
}

package dev.fughetta.dshcompose.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dev.fughetta.dshcompose.ui.notify.NowBarState

/**
 * Samsung's Now Bar (One UI 8, Android 16). Android's own promotion ([LiveActivity]) is necessary
 * there and not enough: One UI keeps a second, separate switch over how it treats a promoted
 * notification, and an app installed by sideload starts without it, so nothing is shown as a live
 * activity even though the notification is promoted.
 *
 * Measured on SM-X936B (One UI 8.5, SDK 36) with an independent third-party probe, A/B on the
 * `Settings.Secure` switch [SETTING] (Developer options → "Live notifications for all apps"): at 1
 * the shade grows One UI's own 「Live notifications」 group at the top and the notification shows
 * there as a live one; at 0 that group and the treatment disappear. The AOSP side does not move
 * either way — the record keeps `PROMOTED_ONGOING` and `canPostPromotedNotifications()` stays true
 * — so Android's promotion cannot be read as "One UI will display it". The switch's title has no
 * zh-rCN translation in SecSettings, so every locale shows the English name.
 *
 * Nothing private is added to the notification: the standard promoted ongoing notification is what
 * One UI takes once the switch is on.
 *
 * Everything here is a read except [enable], which writes that one setting when the person tapped
 * to do exactly that and the app holds `WRITE_SECURE_SETTINGS`.
 */
object NowBar {
    /** `Settings.Secure` key behind Developer options → "Live notifications for all apps". */
    const val SETTING = "enable_notification_nowbar_test"

    /**
     * The feature the Now Bar ships with
     * (`/system/etc/permissions/com.samsung.feature.nowbar.xml`).
     */
    const val FEATURE = "com.samsung.feature.nowbar"

    /**
     * Whether this device decides Now Bar admission on its own: a Samsung build that ships the Now
     * Bar. Elsewhere the field means nothing and the settings page says nothing about it.
     */
    fun relevant(context: Context): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            runCatching { context.packageManager.hasSystemFeature(FEATURE) }.getOrDefault(false)

    /** The switch as read: true admits every app, null when the system does not say. */
    fun allowed(context: Context): Boolean? = runCatching {
        Settings.Secure.getInt(context.contentResolver, SETTING) == 1
    }
        .getOrNull()

    /** [enable] can work here: the gate exists and the app may write secure settings. */
    fun writable(context: Context): Boolean = relevant(context) && granted(context)

    /**
     * Admits every app to the Now Bar, for a person who tapped the button: writes the setting and
     * reads it back. False when the app may not write it, or the system did not take the write.
     */
    fun enable(context: Context): Boolean {
        if (!writable(context)) return false
        val written = runCatching {
            Settings.Secure.putInt(context.contentResolver, SETTING, 1)
        }
            .getOrDefault(false)
        return written && allowed(context) == true
    }

    /** What the settings page should say; pure, so the JVM tests cover it. */
    fun state(relevant: Boolean, allowed: Boolean?): NowBarState =
        when {
            !relevant -> NowBarState.NotApplicable
            allowed == null -> NowBarState.Unknown
            allowed -> NowBarState.Allowed
            else -> NowBarState.Blocked
        }

    /**
     * [state] as it crossed the bridge; anything unexpected reads as [NowBarState.NotApplicable].
     */
    fun parse(wire: Any?): NowBarState =
        NowBarState.values().firstOrNull { it.name == wire } ?: NowBarState.NotApplicable

    /** Developer options, where the switch lives: check it resolves before opening it. */
    fun developerSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    private fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
}

@file:Suppress("REDUNDANT_ELSE_IN_WHEN")

package dev.fughetta.dshcompose.phoneui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.phone.PhonePolicies
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.*
import dev.fughetta.dshcompose.runtime.PhoneStorage
import dev.fughetta.dshcompose.runtime.VendorSettings
import dev.fughetta.dshcompose.ui.phone.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class PhoneControlSnapshot(
    val status: PhoneControlStatus,
    val policies: PhonePolicies,
    val apps: PersistentList<PhoneAppRuleView>,
    val log: PersistentList<PhoneLogView>,
    val access: PhoneAccessView,
    val disclosure: Boolean,
    val storage: Boolean,
    val returnAfterDone: Boolean,
)

internal class PhoneControlSnapshotSource(
    private val app: Context,
    private val control: PhoneControl,
) {
    private val prefs = app.localPreferences("phone_controls_ui")

    fun read(retained: Set<String>): PhoneControlSnapshot {
        val nativePolicies = control.policies()
        val labels = (control.policiesMap()["labels"] as? Map<*, *>).orEmpty()
        val names = linkedMapOf<String, String>()
        val installed = control.system.apps()
        val installedPackages = installed.mapTo(mutableSetOf()) { it.packageName }
        installed.forEach { names[it.packageName] = it.label }
        (nativePolicies.denyPackages +
                nativePolicies.confirmEveryWritePackages +
                PhonePolicies().denyPackages +
                PhonePolicies().confirmEveryWritePackages +
                retained)
            .forEach { pkg -> names.putIfAbsent(pkg, labels[pkg] as? String ?: pkg) }
        val apps =
            names
                .map { (pkg, label) ->
                    PhoneAppRuleView(
                        pkg,
                        label,
                        when (pkg) {
                            in nativePolicies.denyPackages -> PhoneAppRule.Deny
                            in nativePolicies.confirmEveryWritePackages -> PhoneAppRule.Confirm
                            else -> PhoneAppRule.Allow
                        },
                        installed = pkg in installedPackages,
                    )
                }
                .sortedWith(
                    compareBy<PhoneAppRuleView> { it.label.lowercase() }.thenBy { it.packageName },
                )
                .toPersistentList()
        val status = phoneControlStatus(control.status())
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val visited =
            PhoneAccessKind.entries
                .filter { prefs.getBoolean("visited_${it.name}", false) }
                .toPersistentSet()
        val access =
            PhoneAccessView(
                PhoneStorage.isGranted(app),
                status.notifications,
                status.accessibilityEnabled,
                Settings.canDrawOverlays(app) || status.overlay,
                power.isIgnoringBatteryOptimizations(app.packageName),
                VendorSettings.vendorOf(Build.MANUFACTURER, Build.BRAND),
                visited,
            )
        return PhoneControlSnapshot(
            status,
            nativePolicies,
            apps,
            logs(),
            access,
            prefs.getBoolean("disclosureAccepted", false),
            prefs.getBoolean("phoneStorage", false),
            prefs.getBoolean("returnAfterDone", true),
        )
    }

    fun logs(): PersistentList<PhoneLogView> =
        control.log
            .toMaps(300)
            .map { row ->
                PhoneLogView(
                    (row["time"] as? Number)?.toLong() ?: 0,
                    row["tool"] as? String ?: "",
                    row["app"] as? String,
                    row["summary"] as? String ?: "",
                    row["decision"] as? String ?: "",
                )
            }
            .toPersistentList()
}

internal fun phoneControlStatus(value: Map<String, Any?>): PhoneControlStatus {
    fun Map<*, *>.map(key: String) = this[key] as? Map<*, *> ?: emptyMap<Any, Any>()
    fun Map<*, *>.bool(key: String) = this[key] == true
    fun Map<*, *>.str(key: String) = this[key] as? String
    val a11y = value.map("a11y")
    val keeper = value.map("a11yKeeper")
    val task = value.map("task")
    val device = value.map("device")
    val bg = value.map("background")
    val privileged = bg.map("privileged")
    val keep = privileged.map("keep")
    val shizuku = bg.map("shizuku")
    val adb = bg.map("adb")
    val root = bg.map("root")
    return PhoneControlStatus(
        value.bool("enabled"),
        value.bool("bridgeListening"),
        a11y.bool("enabled"),
        a11y.bool("serviceConnected"),
        value.bool("overlay"),
        value.bool("notifications"),
        value.bool("confirmationNotifications"),
        keeper.bool("granted"),
        keeper.str("command"),
        device.str("restricted"),
        (device["sdkInt"] as? Number)?.toInt() ?: 0,
        PhoneTaskView(
            (task["id"] as? Number)?.toLong(),
            task.bool("active"),
            task.str("app"),
            task.bool("paused"),
            task.bool("stopped"),
            task.bool("waived"),
        ),
        PhoneBackgroundView(
            privileged.str("mode") ?: "off",
            privileged.bool("ready"),
            privileged.str("reason"),
            (privileged["supportedModes"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.toPersistentList() ?: persistentListOf("off"),
            privileged.str("recommendedMode") ?: "off",
            privileged.str("grantPath") ?: "setup",
            shizuku.str("provider"),
            shizuku.bool("running"),
            shizuku.bool("granted"),
            adb.bool("paired"),
            adb.bool("connected"),
            adb.bool("wirelessDebugging"),
            root.bool("connected"),
            privileged.bool("rootSelected"),
            keep.bool("enabled"),
            keep.str("phase"),
            keep.str("reasonCode"),
        ),
    )
}

internal fun phoneAccessIntent(
    app: Context,
    control: PhoneControl,
    kind: PhoneAccessKind,
): Intent? {
    val uri = Uri.parse("package:${app.packageName}")
    return when (kind) {
        PhoneAccessKind.AllFiles -> PhoneStorage.allFilesAccessIntent(app)
        PhoneAccessKind.Accessibility -> control.accessibilitySettingsIntent()
        PhoneAccessKind.AppDetails -> control.appDetailsIntent()
        PhoneAccessKind.Battery -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        PhoneAccessKind.Overlay -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
        PhoneAccessKind.Notifications ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        PhoneAccessKind.WirelessDebugging ->
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        PhoneAccessKind.VendorBackground,
        PhoneAccessKind.Autostart,
        PhoneAccessKind.Popup -> phoneVendorIntent(app, control, kind)
        else -> null
    }
}

private fun phoneVendorIntent(app: Context, control: PhoneControl, kind: PhoneAccessKind): Intent {
    val vendor = VendorSettings.vendorOf(Build.MANUFACTURER, Build.BRAND)
    val setting =
        when (kind) {
            PhoneAccessKind.Autostart -> VendorSettings.Switch.AUTOSTART
            PhoneAccessKind.Popup -> VendorSettings.Switch.POPUP
            else -> VendorSettings.Switch.BACKGROUND
        }
    for (page in VendorSettings.pages(vendor, setting)) {
        val intent =
            if (page.cls != null) Intent().setComponent(ComponentName(page.pkg, page.cls))
            else Intent(page.action).setPackage(page.pkg)
        page.packageExtra?.let { intent.putExtra(it, app.packageName) }
        page.labelExtra?.let {
            intent.putExtra(it, app.applicationInfo.loadLabel(app.packageManager).toString())
        }
        val resolved = app.packageManager.resolveActivity(intent, 0)?.activityInfo
        if (resolved?.exported == true) return intent
    }
    return control.appDetailsIntent()
}

internal fun phoneControlFailure(app: Context, code: String): String =
    when (code) {
        "phone/cancel-local-turns" ->
            AppStrings.get(app, UiR.string.phone_controls_cancel_turns_failed)
        "phone/package" -> AppStrings.get(app, UiR.string.phone_controls_invalid_package)
        "phone/task-active" -> AppStrings.get(app, UiR.string.phone_controls_unlock_busy)
        "phone/storage" -> AppStrings.get(app, UiR.string.phone_controls_storage_denied)
        "phone/pairing-code" -> AppStrings.get(app, UiR.string.phone_controls_pairing_code_invalid)
        "phone/pairing-port" -> AppStrings.get(app, UiR.string.phone_controls_pairing_port_invalid)
        "phone/selection-changed" ->
            AppStrings.get(app, UiR.string.phone_controls_selection_changed)
        "phone/read" -> AppStrings.get(app, UiR.string.phone_controls_read_failed)
        "phone/change",
        "phone/preferences",
        "phone/permission",
        "phone/mode",
        "phone/unlock" -> AppStrings.get(app, UiR.string.phone_controls_change_failed)
        else -> code
    }

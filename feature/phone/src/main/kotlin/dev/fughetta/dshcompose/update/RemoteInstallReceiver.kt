package dev.fughetta.dshcompose.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Explicit PackageInstaller callback; Android may recreate the process to deliver it. */
class RemoteInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = RemoteInstallStore(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            recoverInstallation(context, store)
            return
        }
        val record = store.read() ?: return
        if (
            record.id != intent.getStringExtra("requestId") ||
                record.terminal ||
                record.installerSession != intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        )
            return
        when (
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val control = dev.fughetta.dshcompose.phone.PhoneControl.get(context)
                if (
                    !control.isEnabled() ||
                        control.environment.isLocked() ||
                        control.tasks.snapshot().stopped
                ) {
                    store.update(record.id) {
                        it.copy(status = "cancelled", message = "INSTALL_STOPPED")
                    }
                    runCatching {
                        context.packageManager.packageInstaller.abandonSession(
                            record.installerSession,
                        )
                    }
                    return
                }
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (
                    confirmation == null ||
                        runCatching {
                            context.startActivity(
                                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                            .isFailure
                ) {
                    store.update(record.id) {
                        it.copy(status = "failed", message = "INSTALL_CONFIRMATION_UNAVAILABLE")
                    }
                    runCatching {
                        context.packageManager.packageInstaller.abandonSession(
                            record.installerSession,
                        )
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> recoverInstallation(context, store)
            else ->
                store.update(record.id) {
                    it.copy(
                        status =
                            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled"
                            else "failed",
                        message = "INSTALL_FAILED_$status",
                    )
                }
        }
    }
}

internal fun recoverInstallation(context: Context, store: RemoteInstallStore) {
    val record = store.read()?.takeIf { it.status == "awaiting-install" } ?: return
    val current = context.packageManager.getPackageInfo(context.packageName, 0)
    val version =
        if (Build.VERSION.SDK_INT >= 28) current.longVersionCode
        else {
            @Suppress("DEPRECATION") current.versionCode.toLong()
        }
    if (current.lastUpdateTime > record.previousUpdate && version == record.version) {
        store.update(record.id) {
            it.copy(
                status = "installed",
                message = "INSTALL_SUCCESS: ${context.packageName}; version=$version",
            )
        }
    } else if (
        context.packageManager.packageInstaller.getSessionInfo(record.installerSession) == null ||
            System.currentTimeMillis() - record.startedAt > 15 * 60_000
    ) {
        runCatching {
            context.packageManager.packageInstaller.abandonSession(record.installerSession)
        }
        store.update(record.id) {
            it.copy(
                status = "unknown",
                message = "Installation outcome is unconfirmed; do not retry automatically.",
            )
        }
    }
}

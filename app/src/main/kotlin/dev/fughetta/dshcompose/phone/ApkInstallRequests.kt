package dev.fughetta.dshcompose.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.phone.ConfirmRequest
import dev.fughetta.dshcompose.domain.phone.Decision
import dev.fughetta.dshcompose.files.GuestFiles
import dev.fughetta.dshcompose.i18n.AppStrings
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Installs only the staged bytes described in the person's single-use native confirmation. */
class ApkInstallRequests(
    private val app: Context,
    private val tasks: PhoneTasks,
    private val prompts: PhonePrompts,
    private val locked: () -> Boolean,
    private val device: PhoneDevice,
) {
    private var deniedOwner: PhoneTasks.Owner? = null

    fun install(
        path: String,
        deviceId: String,
        scope: SerialWorker.Scope,
        remoteSource: File? = null,
        remoteRecord: dev.fughetta.dshcompose.update.RemoteInstallRecord? = null,
    ): ToolOutput {
        if (
            dev.fughetta.dshcompose.update.RemoteInstallStore(app).read()?.status ==
                "awaiting-install"
        )
            return failure("INSTALL_BUSY: A self-update is awaiting its installation result.")
        if (deviceId != device.id) return failure("INSTALL_DEVICE_MISMATCH")
        if (deniedOwner?.let(tasks::isCurrent) == true) return failure("INSTALL_PREVIOUSLY_DENIED")
        if (!ApkInstallGate.acquire()) return failure("INSTALL_BUSY")
        val owner = tasks.activeOwner()
        val installer = app.packageManager.packageInstaller
        var sessionId: Int? = null
        var receiver: BroadcastReceiver? = null
        var pending: PendingIntent? = null
        var inspected: File? = null
        val live = java.util.concurrent.atomic.AtomicBoolean(true)
        var handedOff = false
        try {
            if (owner == null || locked()) return failure("INSTALL_STOPPED")
            if (!app.packageManager.canRequestPackageInstalls())
                return failure(
                    "INSTALL_PERMISSION_REQUIRED: Ask the user to enable Install unknown apps for DSH Compose in Android Settings. Do not automate this step.",
                )
            val source =
                remoteSource
                    ?: GuestFiles.resolve(app, path)
                    ?: return failure("INSTALL_FILE_UNREADABLE")
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setSize(source.length())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            setRequireUserAction(
                                PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                            )
                    }
            val id = installer.createSession(params)
            sessionId = id
            installer.openSession(id).use { session ->
                source.inputStream().use { input ->
                    session.openWrite("base.apk", 0, -1).use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted || !tasks.isCurrent(owner))
                                throw InterruptedException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_APK_BYTES) return failure("INSTALL_FILE_TOO_LARGE")
                            output.write(buffer, 0, count)
                        }
                        session.fsync(output)
                    }
                }
                // Inspect a read-back of the install session, then remove the inspection file.
                // The original path is never read again after staging.
                val file = File.createTempFile("install-inspect-", ".apk", app.cacheDir)
                inspected = file
                session.openRead("base.apk").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val info =
                    app.packageManager.getPackageArchiveInfo(file.path, 0)
                        ?: return failure("INSTALL_INVALID_APK")
                if (remoteRecord != null && info.packageName != app.packageName)
                    return failure(
                        "INSTALL_NOT_COMPOSE: Remote installation only updates DSH Compose itself.",
                    )
                val ai = info.applicationInfo ?: return failure("INSTALL_INVALID_APK")
                ai.sourceDir = file.path
                ai.publicSourceDir = file.path
                val label = app.packageManager.getApplicationLabel(ai).toString()
                val installed =
                    try {
                        app.packageManager.getPackageInfo(info.packageName, 0)
                        true
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }
                val version =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                    else {
                        @Suppress("DEPRECATION") info.versionCode.toLong()
                    }
                val text =
                    AppStrings.get(
                        app,
                        if (installed) R.string.phone_install_update
                        else R.string.phone_install_new,
                        label,
                        info.packageName,
                        info.versionName.orEmpty(),
                        version,
                        device.name,
                        device.id,
                    )
                file.delete()
                inspected = null
                val decision = scope.userWait {
                    prompts.confirm(
                        ConfirmRequest(text, label, info.packageName, firstInApp = false),
                    )
                }
                if (decision != Decision.ALLOW_ONCE) {
                    deniedOwner = owner
                    return failure(
                        if (decision == Decision.UNAVAILABLE) "INSTALL_CONFIRMATION_UNAVAILABLE"
                        else "INSTALL_DENIED",
                    )
                }
                if (!tasks.isCurrent(owner) || locked() || Thread.currentThread().isInterrupted)
                    return failure("INSTALL_STOPPED")
                if (remoteRecord != null) {
                    val store = dev.fughetta.dshcompose.update.RemoteInstallStore(app)
                    val previous =
                        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                    store.write(
                        remoteRecord.copy(
                            status = "awaiting-install",
                            installerSession = id,
                            version = version,
                            previousUpdate = previous,
                            startedAt = System.currentTimeMillis(),
                        ),
                    )
                    val delivery =
                        PendingIntent.getBroadcast(
                            app,
                            id,
                            Intent(
                                    app,
                                    dev.fughetta.dshcompose.update.RemoteInstallReceiver::class
                                        .java,
                                )
                                .setAction("${app.packageName}.REMOTE_INSTALL.$id")
                                .putExtra("requestId", remoteRecord.id),
                            PendingIntent.FLAG_UPDATE_CURRENT or
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                    PendingIntent.FLAG_MUTABLE
                                else 0,
                        )
                    session.commit(delivery.intentSender)
                    handedOff = true
                    return ToolOutput(
                        "INSTALL_AWAITING_CONFIRMATION: Reopen DSH Compose after the update; the result will be reported to the host.",
                    )
                }
                val result = CompletableFuture<Int>()
                val action = "${app.packageName}.INSTALL_APK.${UUID.randomUUID()}"
                val callback =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (!live.get() || result.isDone) return
                            val status =
                                intent.getIntExtra(
                                    PackageInstaller.EXTRA_STATUS,
                                    PackageInstaller.STATUS_FAILURE,
                                )
                            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                                if (!tasks.isCurrent(owner) || locked()) {
                                    result.complete(PackageInstaller.STATUS_FAILURE_ABORTED)
                                    return
                                }
                                @Suppress("DEPRECATION")
                                val dialog = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                if (
                                    dialog == null ||
                                        runCatching {
                                            app.startActivity(
                                                dialog.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                            .isFailure
                                )
                                    result.complete(PackageInstaller.STATUS_FAILURE_BLOCKED)
                            } else result.complete(status)
                        }
                    }
                receiver = callback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    app.registerReceiver(
                        callback,
                        IntentFilter(action),
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                else
                    app.registerReceiver(
                        callback,
                        IntentFilter(action),
                        "android.permission.INSTALL_PACKAGES",
                        Handler(Looper.getMainLooper()),
                    )
                val delivery =
                    PendingIntent.getBroadcast(
                        app,
                        id,
                        Intent(action).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                PendingIntent.FLAG_MUTABLE
                            else 0,
                    )
                pending = delivery
                session.commit(delivery.intentSender)
                val status = scope.userWait {
                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
                    while (true) {
                        if (!tasks.isCurrent(owner) || locked())
                            return@userWait PackageInstaller.STATUS_FAILURE_ABORTED
                        if (System.nanoTime() >= deadline) return@userWait Int.MIN_VALUE
                        try {
                            return@userWait result.get(250, TimeUnit.MILLISECONDS)
                        } catch (_: TimeoutException) {}
                    }
                    @Suppress("UNREACHABLE_CODE") PackageInstaller.STATUS_FAILURE
                }
                return when (status) {
                    PackageInstaller.STATUS_SUCCESS ->
                        ToolOutput("INSTALL_SUCCESS: ${info.packageName}; deviceId=${device.id}")
                    PackageInstaller.STATUS_FAILURE_ABORTED -> {
                        deniedOwner = owner
                        failure("INSTALL_CANCELLED")
                    }
                    Int.MIN_VALUE -> {
                        deniedOwner = owner
                        failure("INSTALL_TIMEOUT: Outcome unconfirmed. Do not retry automatically.")
                    }
                    else -> failure("INSTALL_FAILED_$status")
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure("INSTALL_STOPPED")
        } catch (_: Exception) {
            return failure("INSTALL_FAILED")
        } finally {
            live.set(false)
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            pending?.cancel()
            if (!handedOff) sessionId?.let { runCatching { installer.abandonSession(it) } }
            inspected?.delete()
            ApkInstallGate.release()
        }
    }

    private fun failure(code: String) = ToolOutput(code, isError = true)

    companion object {
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}

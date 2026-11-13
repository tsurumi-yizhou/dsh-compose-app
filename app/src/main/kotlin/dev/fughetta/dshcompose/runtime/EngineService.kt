package dev.fughetta.dshcompose.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.fughetta.dshcompose.ClientApplication
import dev.fughetta.dshcompose.MainActivity
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.di.createLocalSessions
import dev.fughetta.dshcompose.engine.runtime.*
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.PhoneControl
import dev.fughetta.dshcompose.sessions.foreground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Foreground service that keeps the local DSH process alive in the background. */
class EngineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activityJob: Job? = null
    private var activeTasks = false
    private var destroyed = false
    private var activitySink: RuntimeController.EventSink? = null
    private val hosts
        get() = (application as ClientApplication).hostsRepository

    private val sessions by lazy {
        createLocalSessions(
            scope = scope,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "native",
            locale = { resources.configuration.locales[0].toLanguageTag() },
            changed = ::refreshActiveTasks,
            started = {
                activeTasks = true
                acquireWakeLock()
                refreshActiveTasks()
            },
            finished = { refreshActiveTasks() },
            failed = {
                activityJob?.cancel()
                activeTasks = false
                releaseWakeLock()
            },
        )
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var installSink: RuntimeController.EventSink? = null
    private var installStep: String? = null
    private var installPercent = -1

    override fun onCreate() {
        super.onCreate()
        val controller = AppRuntime.get(this)
        val sink = RuntimeController.EventSink { event ->
            if (!destroyed) {
                when (event["type"]) {
                    "status" -> updateRuntimeActivity(controller, event["status"] as? Map<*, *>)
                    "progress",
                    "logs" -> {
                        if (
                            controller.phase == RuntimeController.Phase.INSTALLING ||
                                controller.phase == RuntimeController.Phase.STARTING
                        )
                            acquireWakeLock()
                    }
                }
            }
        }
        activitySink = sink
        controller.addSink(sink)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = AppRuntime.get(this)
        when (intent?.action) {
            ACTION_INSTALL -> {
                // First unpack or an upgrade: keep the process (and the CPU) up until it is done.
                ensureChannel(this)
                installStep = "extract"
                installPercent = -1
                // The last unpack's "ready" / "stopped" is old news now.
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                    INSTALL_ALERT_ID,
                )
                foreground(buildInstallNotification(this, installPercent, installStep))
                acquireWakeLock()
                if (installSink == null) {
                    val sink = RuntimeController.EventSink { event ->
                        if (event["type"] == "progress") {
                            val done = (event["done"] as? Number)?.toLong() ?: 0L
                            val total = (event["total"] as? Number)?.toLong() ?: 0L
                            val percent =
                                if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                            val step = event["step"] as? String
                            if (percent != installPercent || step != installStep) {
                                installStep = step
                                installPercent = percent
                                val nm =
                                    getSystemService(Context.NOTIFICATION_SERVICE)
                                        as NotificationManager
                                nm.notify(
                                    NOTIFICATION_ID,
                                    buildInstallNotification(this, percent, step),
                                )
                            }
                        }
                    }
                    installSink = sink
                    controller.addSink(sink)
                }
                return START_NOT_STICKY
            }
            ACTION_INSTALL_DONE -> {
                installSink?.let { controller.removeSink(it) }
                installSink = null
                updateRuntimeActivity(controller, controller.status())
                if (
                    controller.phase != RuntimeController.Phase.STARTING &&
                        controller.phase != RuntimeController.Phase.RUNNING
                ) {
                    stopForegroundCompat()
                    stopSelf(startId)
                }
                postInstallAlert(this, intent.getBooleanExtra(EXTRA_INSTALL_OK, true))
                return START_NOT_STICKY
            }
            ACTION_PHONE_STOP -> {
                // 「停止操作」 on the notification: stop the phone task, keep the engine.
                // l10n:keep agent stop reason
                PhoneControl.get(this).stopTask("用户在通知里停止")
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                if (controller.phase == RuntimeController.Phase.INSTALLING) return START_NOT_STICKY
                controller.stopProcess()
                running = false
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel(this)
                val notification = buildNotification(this, controller)
                foreground(notification)
                running = true
                if (controller.phase != RuntimeController.Phase.RUNNING) acquireWakeLock()
                else updateRuntimeActivity(controller, controller.status())
                registerNetworkCallback(controller)
                logBackgroundPolicy(controller)
                controller.startProcess()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        destroyed = true
        activitySink?.let { AppRuntime.get(this).removeSink(it) }
        activitySink = null
        hosts.releaseLocalObserver(sessions)
        scope.cancel()
        installSink?.let { AppRuntime.get(this).removeSink(it) }
        installSink = null
        networkCallback?.let { callback ->
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        AppRuntime.get(this).onNetworkWatchStopped()
        releaseWakeLock()
        wakeLock = null
        super.onDestroy()
    }

    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerNetworkCallback(controller: RuntimeController) {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    controller.refreshDns(properties)
                }

                // API 29+: the system cut (or restored) this uid's network, e.g. a vendor
                // background policy.
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    controller.onNetworkBlocked(blocked, MainActivity.inForeground)
                }
            }
        if (runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess)
            networkCallback = callback
    }

    private fun logBackgroundPolicy(controller: RuntimeController) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val line =
            runCatching {
                NetworkAccess.policyLine(
                    batteryExempt = pm.isIgnoringBatteryOptimizations(packageName),
                    dataSaverStatus = cm.restrictBackgroundStatus,
                    notifications = nm.areNotificationsEnabled(),
                )
            }
                .getOrNull() ?: return
        controller.note(line)
    }

    private fun updateRuntimeActivity(controller: RuntimeController, status: Map<*, *>?) {
        val identity =
            if (controller.phase == RuntimeController.Phase.RUNNING) {
                val url = status?.get("baseUrl") as? String
                val token = status?.get("token") as? String
                if (url != null && token != null) url to token else null
            } else null
        hosts.observeLocal(sessions, identity)
        when (controller.phase) {
            RuntimeController.Phase.INSTALLING,
            RuntimeController.Phase.STARTING -> acquireWakeLock()
            RuntimeController.Phase.RUNNING -> if (!activeTasks) releaseWakeLock()
            else -> releaseWakeLock()
        }
    }

    /** Host events start/stop renewal; only confirmed running sessions keep it going. */
    private fun refreshActiveTasks() {
        activityJob?.cancel()
        if (destroyed) return
        if (sessions.lease == null) {
            activeTasks = false
            releaseWakeLock()
            return
        }
        activityJob = scope.launch {
            try {
                do {
                    val running =
                        withTimeoutOrNull(15_000L) { hosts.runningLocalSessions(sessions) }
                    activeTasks = !running.isNullOrEmpty()
                    if (!activeTasks) {
                        releaseWakeLock()
                        return@launch
                    }
                    acquireWakeLock()
                    delay(WAKE_LOCK_RENEW_MS)
                } while (true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                activeTasks = false
                releaseWakeLock()
            }
        }
    }

    private fun acquireWakeLock() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        if (wakeLock?.isHeld == true && now - wakeLockRenewedAt < WAKE_LOCK_RENEW_MS) return
        val lock =
            wakeLock
                ?: run {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh-app:runtime").apply {
                        setReferenceCounted(false)
                        wakeLock = this
                    }
                }
        // Non-reference-counted acquire replaces the previous timeout without a release gap.
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLockRenewedAt = now
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        const val ACTION_INSTALL = "dev.fughetta.dshcompose.INSTALL"
        const val ACTION_INSTALL_DONE = "dev.fughetta.dshcompose.INSTALL_DONE"
        const val ACTION_START = "dev.fughetta.dshcompose.START"
        const val ACTION_STOP = "dev.fughetta.dshcompose.STOP"
        const val ACTION_PHONE_STOP = "dev.fughetta.dshcompose.PHONE_STOP"

        /** With [ACTION_INSTALL_DONE]: the unpack finished (true) or stopped. */
        const val EXTRA_INSTALL_OK = "ok"
        private const val WAKE_LOCK_TIMEOUT_MS = 180_000L
        private const val WAKE_LOCK_RENEW_MS = 60_000L
        private const val CHANNEL_ID = "dsh-runtime"
        private const val NOTIFICATION_ID = 3080
        private const val ALERT_ID = 3081
        private const val ALERT_CHANNEL = "dsh-runtime-alert"
        private const val INSTALL_ALERT_ID = 3082
        private const val INSTALL_CHANNEL = "dsh-runtime-ready"

        private fun openApp(context: Context, id: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The unpack ended while the app was away: ready, or stopped (see [InstallAlert]). */
        private fun postInstallAlert(context: Context, ok: Boolean) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alert =
                InstallAlert.choose(ok, MainActivity.inForeground, nm.areNotificationsEnabled())
                    ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    INSTALL_CHANNEL,
                    AppStrings.get(context, R.string.install_alert_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val (title, text) =
                when (alert) {
                    InstallAlert.READY ->
                        R.string.install_ready_title to R.string.install_ready_text
                    InstallAlert.FAILED ->
                        R.string.install_failed_title to R.string.install_failed_text
                }
            val notification =
                Notification.Builder(context, INSTALL_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_dsh)
                    .setContentTitle(AppStrings.get(context, title))
                    .setContentText(AppStrings.get(context, text))
                    .setContentIntent(openApp(context, INSTALL_ALERT_ID))
                    .setAutoCancel(true)
                    .build()
            nm.notify(INSTALL_ALERT_ID, notification)
        }

        fun cancelStoppedAlert(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                ALERT_ID,
            )
        }

        /** DSH died and won't come back by itself; in the app the failure card says so instead. */
        fun postStoppedAlert(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (MainActivity.inForeground || !nm.areNotificationsEnabled()) return
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL,
                    AppStrings.get(context, R.string.service_stopped_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    ALERT_ID,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                Notification.Builder(context, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_dsh)
                    .setContentTitle(AppStrings.get(context, R.string.service_stopped_title))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            nm.notify(ALERT_ID, notification)
        }

        /** Unpacking the runtime: progress, no 停止 (an interrupted unpack only starts over). */
        private fun buildInstallNotification(
            context: Context,
            percent: Int,
            step: String?,
        ): Notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(AppStrings.APP_NAME)
                .setContentText(
                    AppStrings.get(
                        context,
                        dev.fughetta.dshcompose.ui.runtime.engineInstallStageLabel(step),
                    ),
                )
                .setProgress(100, percent.coerceAtLeast(0), percent < 0)
                .setContentIntent(openApp(context, NOTIFICATION_ID))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

        @Volatile private var running = false

        /** The service is in the foreground (so the controller may start DSH directly). */
        val isRunning: Boolean
            get() = running

        fun updateNotification(context: Context) {
            if (!running) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(context, AppRuntime.get(context)))
        }

        fun stopSelfIfRunning(context: Context) {
            if (running)
                context.startService(
                    Intent(context, EngineService::class.java).setAction(ACTION_STOP),
                )
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                        CHANNEL_ID,
                        AppStrings.get(context, R.string.service_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description = AppStrings.get(context, R.string.service_channel_description)
                        setShowBadge(false)
                    },
            )
        }

        private fun buildNotification(
            context: Context,
            controller: RuntimeController,
        ): Notification {
            val text =
                when (controller.phase) {
                    RuntimeController.Phase.STARTING ->
                        AppStrings.get(context, R.string.service_starting)
                    RuntimeController.Phase.RUNNING ->
                        AppStrings.get(context, R.string.service_running)
                    RuntimeController.Phase.STOPPING ->
                        AppStrings.get(context, R.string.service_stopping)
                    else -> AppStrings.get(context, R.string.service_title)
                }
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val stop =
                PendingIntent.getService(
                    context,
                    1,
                    Intent(context, EngineService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION") Notification.Builder(context)
                }
            // While DSH operates the phone: say what it does and offer 停止操作.
            val phoneLine = PhoneControl.peek()?.foregroundLine()
            if (phoneLine != null) {
                val stopPhone =
                    PendingIntent.getService(
                        context,
                        2,
                        Intent(context, EngineService::class.java).setAction(ACTION_PHONE_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                builder.addAction(
                    Notification.Action.Builder(
                            null,
                            AppStrings.get(context, R.string.service_stop_phone),
                            stopPhone,
                        )
                        .build(),
                )
            }
            return builder
                .setSmallIcon(R.drawable.ic_stat_dsh)
                .setContentTitle(AppStrings.APP_NAME)
                .setContentText(phoneLine ?: text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(
                    Notification.Action.Builder(
                            null,
                            AppStrings.get(context, R.string.service_stop),
                            stop,
                        )
                        .build(),
                )
                .build()
        }
    }
}

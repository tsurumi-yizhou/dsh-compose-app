package dev.fughetta.dshcompose.phone

import android.os.Build
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.notify.LiveActivity
import dev.fughetta.dshcompose.phone.PhoneControl.Companion.K_TCP_FALLBACK
import dev.fughetta.dshcompose.phone.PhoneControl.Companion.get
import dev.fughetta.dshcompose.phone.a11y.A11yKeeper
import dev.fughetta.dshcompose.phone.background.SecureSettingsGrant
import dev.fughetta.dshcompose.runtime.EngineService
import java.util.concurrent.TimeUnit

internal fun PhoneControl.onTask(s: PhoneTasks.Snapshot) {
    updateTicker()
    prompts.check()
    if (s.active && s.id != keptTask) {
        keptTask = s.id
        ticker.execute(::keep)
    }
    if (!s.active) {
        tasks.runIfInactive(s) {
            if (s.stopped && tasks.isStopped()) core.worker.interruptCurrent()
            statusLine = null
        }
        runCatching {
            PhoneRegistry.overlayGate?.close { cleanup -> tasks.runIfInactive(s, cleanup) }
        }
        // One virtual display per task: release it when the task ends.
        // IPC must stay outside the PhoneTasks lock; the background owner
        // check and release are serialized with its next registration.
        runCatching { background.endTask(s.id) }
    }
    EngineService.updateNotification(app)
    LiveActivity.refresh(app)
    emitStatus()
}

internal fun PhoneControl.setStatusLine(text: String?) {
    if (statusLine == text) return
    statusLine = text
    EngineService.updateNotification(app)
    LiveActivity.refresh(app)
}

/**
 * What the live activity shows while a task runs: what DSH does now, in which app; null otherwise.
 */
internal fun PhoneControl.backgroundStatus(): Map<String, Any?> =
    try {
        background.status()
    } catch (e: Exception) {
        linkedMapOf(
            "mode" to "off",
            "ready" to false,
            "reason" to (e.message ?: AppStrings.get(app, R.string.phone_background_error)),
            "androidOk" to (Build.VERSION.SDK_INT >= 34),
        )
    }

/**
 * 自动恢复无障碍: whether it may work (the permission), the one-tap way to it, the command for the other.
 */
internal fun PhoneControl.keeperStatus(): Map<String, Any?> {
    val granted = A11yKeeper.granted(app)
    return linkedMapOf(
        "granted" to granted,
        "path" to
            runCatching { background.grantPath(granted) }.getOrDefault(SecureSettingsGrant.SETUP),
        "command" to SecureSettingsGrant.command(app.packageName),
    )
}

/**
 * 自动恢复无障碍 → the one tap: the shell-uid engine grants WRITE_SECURE_SETTINGS over Shizuku or
 * self-ADB. Shizuku without its own permission asks for that first (`pending`) and grants once the
 * person allows. Blocks; the plugin thread.
 */
internal fun PhoneControl.pushIfChanged(force: Boolean) {
    if (sinks.isEmpty()) return
    val s =
        try {
            status()
        } catch (_: Exception) {
            return
        }
    if (!force && s == lastStatus) return
    lastStatus = s
    main.post { sinks.forEach { runCatching { it(s) } } }
}

/**
 * Every 2 s: end idle tasks; 自动恢复无障碍; push status changes we can't observe (accessibility
 * connecting).
 */
internal fun PhoneControl.updateTicker() {
    // Keep scheduling decisions on the same executor as tick; read current state there.
    ticker.execute {
        if (hasTickWork()) {
            if (tickFuture == null)
                tickFuture = ticker.scheduleWithFixedDelay({ tick() }, 2, 2, TimeUnit.SECONDS)
        } else {
            tickFuture?.cancel(false)
            tickFuture = null
        }
    }
}

internal fun PhoneControl.hasTickWork(): Boolean =
    isEnabled() && (tasks.isActive || sinks.isNotEmpty())

internal fun PhoneControl.tick() {
    if (!hasTickWork()) {
        updateTicker()
        return
    }
    try {
        tasks.expireIdle()
        A11yKeeper.check(app)
        pushIfChanged(force = false)
    } catch (_: Exception) {}
}

// ── Overlay actions (the overlay's 暂停 / 停止 / 插话, the service's own stops) ──

internal fun PhoneControl.checkReached() {
    if (!bridge.listening || bridge.authenticatedConnections > 0) return
    val ep = bridge.endpoint ?: return
    if (ep.tcpPort != null) {
        logger(
            "[dsh-app] phone control: DSH did not connect to the phone bridge (unix socket nor loopback)",
        )
        return
    }
    logger(
        "[dsh-app] phone control: DSH did not reach the unix socket; adding the loopback fallback",
    )
    try {
        val next = bridge.addTcp() ?: return
        rootfs?.let { PhoneGuest.writeConfig(it, next) }
        prefs.edit().putBoolean(K_TCP_FALLBACK, true).apply()
    } catch (e: Exception) {
        logger("[dsh-app] phone control: loopback fallback failed: ${e.message}")
    }
    emitStatus()
}

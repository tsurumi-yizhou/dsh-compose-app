package dev.fughetta.dshcompose.phone.background

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.provider.Settings

internal fun BackgroundControl.restore(ticket: PrivilegedKeepState.Ticket): PrivilegedChannel? {
    val observed = gate.snapshot
    var failure: String? = "connectFailed"
    var waiting = false
    var value: PrivilegedChannel? = null
    try {
        if (!keep.current(ticket)) return null
        value =
            when (ticket.mode) {
                "root" ->
                    root
                        .start { keep.current(ticket) }
                        .also {
                            if (it == null) failure = root.status().reasonCode ?: "restoreBusy"
                        }
                "sui",
                "shizuku" -> {
                    val facts = shizuku.status()
                    failure =
                        when {
                            !facts.running -> "serviceUnavailable"
                            facts.provider != ticket.mode -> "providerChanged"
                            !facts.granted -> "permissionRequired"
                            else -> null
                        }
                    if (failure != null) {
                        waiting = true
                        null
                    } else
                        shizuku
                            .connect(
                                whenAvailable = { recoveryAvailable(ticket) },
                                mayStart = { keep.current(ticket) },
                            )
                            .also { if (it == null) failure = "connectFailed" }
                }
                "adb" -> {
                    val facts = adb.status()
                    if (!facts.paired) {
                        waiting = true
                        failure = "notPaired"
                        null
                    } else if (!wifiAvailable()) {
                        waiting = true
                        failure = "networkUnavailable"
                        null
                    } else {
                        enableWirelessOnce(ticket, facts.wirelessDebugging)
                        adb.connect(
                                whenAvailable = { recoveryAvailable(ticket) },
                                mayStart = { keep.current(ticket) },
                            )
                            .also { if (it == null) failure = "connectFailed" }
                    }
                }
                else -> null
            }
        // Starter publication owns successful channels. A stale borrower must never close one.
        synchronized(keepGuard) {
            val connected = value
            if (keep.current(ticket) && connected?.alive() == true) {
                channel = connected
                gate.connected(observed, connected)
            }
        }
        return value?.takeIf { keep.current(ticket) && it.alive() }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
    } catch (_: Exception) {
        return null
    } finally {
        val alive = value?.alive() == true
        synchronized(keepGuard) {
            keep.complete(ticket, alive, failure, waiting, SystemClock.elapsedRealtime())
        }
        notifyChanged()
    }
}

/** Only a connect rejected by an older busy owner can register this one-shot callback. */
internal fun BackgroundControl.recoveryAvailable(ticket: PrivilegedKeepState.Ticket) {
    val run =
        synchronized(keepGuard) {
            if (!keep.current(ticket)) return
            if (restoring != null) {
                restoreAgain = true
                false
            } else {
                keep.available(ticket)
                true
            }
        }
    if (run) restoreKeptChannel()
}

internal fun BackgroundControl.transportChanged(source: String) {
    notifyChanged()
    // These callbacks may run on main; startup/PROBE always run on recovery's single slot.
    if (
        keep.enabled && (mode == source || source == "sharedApi" && mode in setOf("sui", "shizuku"))
    )
        restoreKeptChannel()
}

internal fun BackgroundControl.wifiAvailable(): Boolean = runCatching {
    val cm = app.getSystemService(ConnectivityManager::class.java)
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ==
        true
}
    .getOrDefault(false)

internal fun BackgroundControl.enableWirelessOnce(
    ticket: PrivilegedKeepState.Ticket,
    wireless: Boolean?,
) {
    if (wireless == true || !secureSettingsGranted()) return
    if (
        runCatching {
                Settings.Global.getInt(
                    app.contentResolver,
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                    0,
                )
            }
            .getOrDefault(0) != 1
    )
        return
    if (keep.takeWirelessAttempt(ticket))
        runCatching { Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1) }
}

internal fun BackgroundControl.updateKeepListeners() {
    if (keep.enabled && mode in setOf("shizuku", "sui")) shizuku.ensureListeners()
    synchronized(networkGuard) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (keep.enabled && mode == "adb") {
            if (networkCallback == null) {
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            notifyChanged()
                            val current = synchronized(networkGuard) { networkCallback === this }
                            if (current && keep.enabled && mode == "adb") restoreKeptChannel()
                        }

                        override fun onLost(network: Network) {
                            notifyChanged()
                        }
                    }
                runCatching {
                    cm.registerNetworkCallback(
                        NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build(),
                        callback,
                    )
                    networkCallback = callback
                }
            }
        } else {
            networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            networkCallback = null
        }
    }
}

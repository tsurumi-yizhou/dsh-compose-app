package dev.fughetta.dshcompose.phoneui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.fughetta.dshcompose.domain.performance.DeviceProfile
import dev.fughetta.dshcompose.domain.performance.ThermalPolicy
import dev.fughetta.dshcompose.performance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns the thermal listener for the native App lifetime. */
class PhonePerformanceMonitor(context: Context, parentScope: CoroutineScope) : AutoCloseable {
    private val app = context.applicationContext
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val main = Handler(Looper.getMainLooper())
    private val policy = ThermalPolicy()
    private val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private var closed = false
    private var facts: DeviceProfile? = null
    private val mutable = MutableStateFlow<Map<String, Any?>>(emptyMap())
    val state: StateFlow<Map<String, Any?>> = mutable.asStateFlow()
    private val recovery = Runnable {
        if (!closed && policy.advance(SystemClock.uptimeMillis())) publish()
    }

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val callback = PowerManager.OnThermalStatusChangedListener { status ->
                if (!closed && policy.update(status, SystemClock.uptimeMillis())) {
                    scheduleRecovery()
                    publish()
                }
            }
            runCatching {
                power?.let { manager ->
                    manager.addThermalStatusListener({ command -> main.post(command) }, callback)
                    listener = callback
                    policy.update(manager.currentThermalStatus, SystemClock.uptimeMillis())
                    scheduleRecovery()
                }
            }
        }
        publish()
        scope.launch {
            val profile = withContext(Dispatchers.IO) { DevicePerformance.read(app) }
            if (!closed) {
                facts = profile
                publish()
            }
        }
    }

    private fun scheduleRecovery() {
        main.removeCallbacks(recovery)
        policy.recoveryAt?.let {
            main.postDelayed(recovery, (it - SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
    }

    private fun publish() {
        ThermalRuntime.lightweight = if (listener != null) policy.lightweight else null
        mutable.value =
            mapOf(
                "device" to facts?.toMap(),
                "thermal" to policy.toMap(Build.VERSION.SDK_INT >= 29, listener != null),
            )
    }

    override fun close() {
        if (closed) return
        closed = true
        lifetime.cancel()
        main.removeCallbacks(recovery)
        if (Build.VERSION.SDK_INT >= 29)
            listener?.let { runCatching { power?.removeThermalStatusListener(it) } }
        listener = null
        ThermalRuntime.lightweight = null
    }
}

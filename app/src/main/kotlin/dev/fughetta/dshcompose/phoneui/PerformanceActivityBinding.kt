package dev.fughetta.dshcompose.phoneui

import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.fughetta.dshcompose.ui.phone.PerformanceSettingsState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Auto requests the fastest same-resolution mode during drawing, releasing it after 700 ms idle.
 */
class PerformanceActivityBinding(
    private val activity: ComponentActivity,
    parentScope: CoroutineScope,
    state: StateFlow<PerformanceSettingsState>,
) : DefaultLifecycleObserver, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val originalMode = activity.window.attributes.preferredDisplayModeId
    private val originalRate = activity.window.attributes.preferredRefreshRate
    private var mode = state.value.refreshRate
    private var active = false
    private var closed = false
    private var ignoreNextDraw = false
    private var listening: ViewTreeObserver? = null
    private val release = Runnable { if (!closed && mode == "auto") request(false) }
    private val requestFast = Runnable { if (!closed && active && mode == "auto") request(true) }
    private val draw = ViewTreeObserver.OnDrawListener {
        if (ignoreNextDraw) ignoreNextDraw = false
        else if (!closed && active && mode == "auto") {
            main.removeCallbacks(release)
            main.postDelayed(release, 700)
            main.removeCallbacks(requestFast)
            main.post(requestFast)
        }
    }
    private val collection = parentScope.launch {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.collect { value ->
                if (mode != value.refreshRate) {
                    mode = value.refreshRate
                    main.removeCallbacks(release)
                    main.removeCallbacks(requestFast)
                    request(active && mode == "max")
                }
            }
        }
    }

    init {
        activity.lifecycle.addObserver(this)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onResume(activity)
    }

    override fun onResume(owner: LifecycleOwner) {
        active = true
        if (listening == null)
            activity.window.decorView.viewTreeObserver.let {
                it.addOnDrawListener(draw)
                listening = it
            }
        request(mode == "max")
    }

    override fun onPause(owner: LifecycleOwner) {
        active = false
        main.removeCallbacks(release)
        main.removeCallbacks(requestFast)
        listening?.takeIf { it.isAlive }?.removeOnDrawListener(draw)
        listening = null
        request(false)
    }

    private fun request(fast: Boolean) {
        val window = activity.window
        val display = window.decorView.display
        val current = display?.mode
        val target =
            if (fast && current != null)
                display.supportedModes
                    .orEmpty()
                    .filter {
                        it.physicalWidth == current.physicalWidth &&
                            it.physicalHeight == current.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
            else null
        val id = target?.modeId ?: 0
        val rate = target?.refreshRate ?: 0f
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == id && attributes.preferredRefreshRate == rate)
            return
        ignoreNextDraw = true
        attributes.preferredDisplayModeId = id
        attributes.preferredRefreshRate = rate
        window.attributes = attributes
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (closed) return
        closed = true
        onPause(activity)
        collection.cancel()
        activity.lifecycle.removeObserver(this)
        activity.window.attributes =
            activity.window.attributes.apply {
                preferredDisplayModeId = originalMode
                preferredRefreshRate = originalRate
            }
    }
}

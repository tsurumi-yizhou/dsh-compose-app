package dev.fughetta.dshcompose.phoneui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.ui.phone.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PerformanceSettingsViewModel(
    context: Context,
    monitor: PhonePerformanceMonitor,
) : ViewModel() {
    private val app = context.applicationContext
    private val scope
        get() = viewModelScope

    private val prefs = NativePerformancePreferences.file(app)
    private val mutable = MutableStateFlow(resolve(monitor.state.value))
    val state: StateFlow<PerformanceSettingsState> = mutable.asStateFlow()
    private var facts = monitor.state.value

    init {
        scope.launch {
            monitor.state.collect {
                facts = it
                mutable.value =
                    resolve(it).copy(busy = mutable.value.busy, error = mutable.value.error)
            }
        }
    }

    fun action(action: PerformanceSettingsAction) {
        if (mutable.value.busy) return
        val edit = prefs.edit()
        when (action) {
            is PerformanceSettingsAction.Mode ->
                if (action.value in setOf("auto", "full", "light"))
                    edit.putString("mode", action.value)
                else return
            is PerformanceSettingsAction.RefreshRate ->
                if (action.value in setOf("auto", "max", "system"))
                    edit.putString("refreshRate", action.value)
                else return
            is PerformanceSettingsAction.Animations -> edit.putBoolean("animations", action.value)
            is PerformanceSettingsAction.Fluid -> edit.putBoolean("fluid", action.value)
            is PerformanceSettingsAction.AutoStartLocal ->
                action.value?.let { edit.putBoolean("autoStartLocal", it) }
                    ?: edit.remove("autoStartLocal")
            PerformanceSettingsAction.ResetEffects -> edit.remove("animations").remove("fluid")
        }
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            val saved =
                try {
                    withContext(Dispatchers.IO) { edit.commit() }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            mutable.value =
                resolve(facts)
                    .copy(
                        error =
                            if (saved) null
                            else
                                AppStrings.get(
                                    app,
                                    UiR.string.phone_controls_performance_save_failed,
                                ),
                    )
        }
    }

    private fun resolve(facts: Map<String, Any?>): PerformanceSettingsState {
        val p = NativePerformancePreferences.read(app)
        val thermal = (facts["thermal"] as? Map<*, *>)?.get("lightweight") == true
        val low = (facts["device"] as? Map<*, *>)?.get("tier") == "low"
        val light =
            thermal ||
                when (p.mode) {
                    "light" -> true
                    "full" -> false
                    else -> low
                }
        val animations = p.animations ?: !light
        return PerformanceSettingsState(
            p.mode,
            p.refreshRate,
            p.animations,
            p.fluid,
            thermal,
            light,
            animations,
            animations && (p.fluid ?: !light),
            autoStartOverride = p.autoStartLocal,
            autoStartLocal = p.autoStartLocal ?: !light,
        )
    }
}

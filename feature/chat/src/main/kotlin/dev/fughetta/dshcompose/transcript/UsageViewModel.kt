package dev.fughetta.dshcompose.transcript

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.di.localPreferences
import dev.fughetta.dshcompose.domain.usage.UsageReading
import dev.fughetta.dshcompose.domain.usage.UsageRepository
import dev.fughetta.dshcompose.ui.transcript.*
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class UsageViewModel(context: Context, createRepository: (CoroutineScope) -> UsageRepository) :
    ViewModel() {
    private val preferences = context.applicationContext.localPreferences("usage_preferences")
    private val mutable =
        MutableStateFlow(
            UsageState(
                threshold =
                    preferences.getString("threshold", "5")?.toBigDecimalOrNull()
                        ?: BigDecimal("5"),
            ),
        )
    val state: StateFlow<UsageState> = mutable.asStateFlow()
    val monitor: UsageRepository = createRepository(viewModelScope)
    private var lastReading = UsageReading()
    private var visible = false
    private var closed = false

    init {
        viewModelScope.launch {
            monitor.state.collect { reading ->
                val previous = lastReading
                lastReading = reading
                mutable.update {
                    val view =
                        if (reading.connection != previous.connection)
                            UsageState(threshold = it.threshold)
                        else it
                    view.copy(
                        hostKey = reading.hostKey,
                        sessionId = reading.sessionId,
                        usage = reading.usage,
                        balance = reading.balance,
                        balanceStatus = reading.balanceStatus,
                        balanceLoading = reading.balanceLoading,
                        balanceCheckedAt = reading.balanceCheckedAt,
                        balanceSource = reading.balanceSource,
                        error = reading.error,
                        dismissed =
                            if (reading.balanceRevision != previous.balanceRevision) false
                            else view.dismissed,
                    )
                }
            }
        }
    }

    fun action(action: UsageAction) {
        if (closed) {
            if (action is UsageAction.CheckKey) action.key.close()
            return
        }
        when (action) {
            UsageAction.Open -> {
                mutable.update { it.copy(open = true) }
                monitor.refresh(immediate = true)
                monitor.observePanel(visible)
            }
            UsageAction.Close -> {
                mutable.update { it.copy(open = false) }
                monitor.observePanel(false)
            }
            is UsageAction.Visibility -> {
                visible = action.visible
                monitor.observePanel(visible && state.value.open)
            }
            UsageAction.Refresh -> monitor.refresh()
            UsageAction.DismissBalance -> mutable.update { it.copy(dismissed = true) }
            is UsageAction.Threshold -> {
                if (action.value !in listOf(0, 1, 5, 10, 20, 50).map { it.toBigDecimal() }) return
                preferences.edit().putString("threshold", action.value.toPlainString()).apply()
                mutable.update { it.copy(threshold = action.value, dismissed = false) }
            }
            is UsageAction.CheckKey -> monitor.checkKey(action.key)
        }
    }

    override fun onCleared() {
        closed = true
        monitor.close()
    }
}

package dev.fughetta.dshcompose.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.settings.Settings
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(val settings: Settings? = null, val errorRevision: Long = 0)

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val errors = MutableStateFlow(0L)
    val uiState =
        repository.data
            .map { SettingsUiState(settings = it) }
            .catch {
                failed()
                emit(SettingsUiState())
            }
            .combine(errors) { state, errors ->
                state.copy(errorRevision = state.errorRevision + errors)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun theme(value: ThemeMode) = change { repository.theme(value) }

    fun dynamicColor(value: Boolean) = change { repository.dynamicColor(value) }

    fun foldCompletedTurns(value: Boolean) = change { repository.foldCompletedTurns(value) }

    private fun change(operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed()
            }
        }
    }

    private fun failed() {
        errors.update { it + 1 }
    }
}

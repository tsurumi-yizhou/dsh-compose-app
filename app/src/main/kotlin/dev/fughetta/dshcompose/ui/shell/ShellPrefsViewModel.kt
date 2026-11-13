package dev.fughetta.dshcompose.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ShellPrefsUiState(val preferences: ShellPrefsState? = null, val errorRevision: Long = 0)

class ShellPrefsViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val errors = MutableStateFlow(0L)
    val uiState =
        repository.shell
            .map { saved ->
                ShellPrefsUiState(
                    ShellPrefsState(
                        saved.quickPanelSwipe,
                        saved.quickPanelItems
                            .filter { featureEntry(it) != null }
                            .toPersistentList(),
                        saved.silencedRisks.toPersistentSet(),
                        saved.recentSearches.toPersistentList(),
                        saved.backgroundGuideSeen,
                    ),
                )
            }
            .catch {
                errors.update { it + 1 }
                emit(ShellPrefsUiState())
            }
            .combine(errors) { state, revision -> state.copy(errorRevision = revision) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellPrefsUiState())

    fun onAction(action: ShellPrefsAction) {
        viewModelScope.launch {
            try {
                when (action) {
                    is ShellPrefsAction.QuickPanelSwipe ->
                        repository.quickPanelSwipe(action.enabled)
                    is ShellPrefsAction.QuickPanelItems ->
                        repository.quickPanelItems(
                            action.items.filter { featureEntry(it) != null }.distinct(),
                        )
                    is ShellPrefsAction.SilenceRisk -> repository.silenceRisk(action.id)
                    is ShellPrefsAction.RememberSearch -> repository.rememberSearch(action.query)
                    ShellPrefsAction.ClearSearches -> repository.clearSearches()
                    ShellPrefsAction.BackgroundGuideSeen -> repository.backgroundGuideSeen()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                errors.update { it + 1 }
            }
        }
    }
}

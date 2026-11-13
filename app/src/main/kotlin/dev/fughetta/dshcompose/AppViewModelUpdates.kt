package dev.fughetta.dshcompose

import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.applyJobs(value: dev.fughetta.dshcompose.ui.jobs.JobsUiState?) {
    mutableState.update { it.copy(jobs = value) }
}

internal fun AppViewModel.applyGoal(value: dev.fughetta.dshcompose.ui.goals.GoalUiState?) {
    mutableState.update { it.copy(goal = value) }
}

internal fun AppViewModel.applySchedules(
    value: dev.fughetta.dshcompose.ui.schedules.SchedulesUiState,
) {
    mutableState.update { it.copy(schedules = value) }
}

internal fun AppViewModel.openScheduledSession(id: String) {
    mutableState.update {
        it.copy(openSessionId = id, openSessionRevision = it.openSessionRevision + 1)
    }
    openSession(id)
}

internal fun AppViewModel.applyShellPreferences(
    view: dev.fughetta.dshcompose.ui.shell.ShellPrefsUiState,
) {
    val failed = view.errorRevision != shellPreferencesErrorRevision
    shellPreferencesErrorRevision = view.errorRevision
    mutableState.update {
        it.copy(
            shellPrefs = view.preferences ?: it.shellPrefs,
            error = if (failed) AppStrings.get(app, UiR.string.settings_unavailable) else it.error,
        )
    }
}

internal fun AppViewModel.applyLanguage(language: String?) {
    mutableState.update { it.copy(language = language) }
    nativePlugins.connect(
        connectionSession?.takeIf { it === client }?.repositories?.pluginUi,
        selectedHost,
        pluginLocale(),
    )
}

internal fun AppViewModel.applySettings(
    view: dev.fughetta.dshcompose.ui.settings.SettingsUiState,
) {
    val failed = view.errorRevision != settingsErrorRevision
    settingsErrorRevision = view.errorRevision
    view.settings?.let { conversations.foldCompletedTurns(it.foldCompletedTurns) }
    mutableState.update { state ->
        val settings = view.settings
        state.copy(
            theme = settings?.theme ?: state.theme,
            dynamicColor = settings?.dynamicColor ?: state.dynamicColor,
            foldCompletedTurns = settings?.foldCompletedTurns ?: state.foldCompletedTurns,
            error =
                if (failed) AppStrings.get(app, UiR.string.settings_unavailable) else state.error,
        )
    }
}

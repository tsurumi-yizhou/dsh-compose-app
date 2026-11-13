package dev.fughetta.dshcompose.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.*
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.schedules.*
import dev.fughetta.dshcompose.ui.search.*

@Composable
internal fun AppNavigationEvents(
    state: AppState,
    backStack: MutableList<NavKey>,
    open: (String) -> Unit,
    goHome: (String?) -> Boolean,
    onAction: (AppAction) -> Unit,
) {
    var consumedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedSessionRevision by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(state.openSessionId, state.openSessionRevision) {
        if (
            consumedSession != state.openSessionId ||
                consumedSessionRevision != state.openSessionRevision
        ) {
            consumedSession = state.openSessionId
            consumedSessionRevision = state.openSessionRevision
            state.openSessionId?.let { open(it) }
        }
    }
    NavigationEvent(state.featurePageRevision) {
        if (state.featurePageRevision > 0) {
            val target = state.featurePage?.let(::featureDestination)
            if (target == Conversations) goHome(null)
            else if (target != null && backStack.lastOrNull() != target) backStack.add(target)
        }
    }
    // The permissions page shows the controller's open state; leaving the page closes it.
    val nativePanel = state.nativePlugins.panel?.panel
    LaunchedEffect(nativePanel) {
        if (nativePanel == null) backStack.remove(NativePluginPage)
        else if (NativePluginPage !in backStack) backStack.add(NativePluginPage)
    }
    val permissionsOpen = state.permissions != null
    LaunchedEffect(permissionsOpen) {
        if (!permissionsOpen) backStack.remove(Permissions)
        else if (backStack.lastOrNull() != Permissions) backStack.add(Permissions)
    }
    val onPermissionsPage = Permissions in backStack
    LaunchedEffect(onPermissionsPage) {
        if (!onPermissionsPage && state.permissions != null) onAction(AppAction.ClosePermissions)
    }
    NavigationEvent(state.openAppearanceRevision) {
        if (state.openAppearanceRevision > 0 && backStack.lastOrNull() != Appearance)
            backStack.add(Appearance)
    }
    LaunchedEffect(state.mediaGeneration.settingsOpen) {
        if (state.mediaGeneration.settingsOpen && backStack.lastOrNull() != MediaSettings)
            backStack.add(MediaSettings)
    }
    LaunchedEffect(state.mediaGeneration.works.open) {
        if (state.mediaGeneration.works.open && backStack.lastOrNull() != MediaWorks)
            backStack.add(MediaWorks)
        else if (
            !state.mediaGeneration.works.open &&
                backStack.lastOrNull() == MediaWorks &&
                backStack.size > 1
        )
            backStack.removeAt(backStack.lastIndex)
    }
    NavigationEvent(state.openAgentsRevision) {
        if (state.openAgentsRevision > 0 && backStack.lastOrNull() != Agents) backStack.add(Agents)
    }
    NavigationEvent(state.openTerminalRevision) {
        if (state.openTerminalRevision > 0 && backStack.lastOrNull() != Terminal)
            backStack.add(Terminal)
    }
    NavigationEvent(state.openPluginSeatsRevision) {
        if (state.openPluginSeatsRevision > 0 && backStack.lastOrNull() != PluginSeats)
            backStack.add(PluginSeats)
    }
    NavigationEvent(state.openEngineSettingsRevision) {
        if (state.openEngineSettingsRevision > 0 && backStack.lastOrNull() != EngineConfiguration)
            backStack.add(EngineConfiguration)
    }
    NavigationEvent(state.openSchedulesRevision) {
        if (state.openSchedulesRevision > 0 && backStack.lastOrNull() != Schedules)
            backStack.add(Schedules)
    }
    LaunchedEffect(state.deletedSessionId) {
        state.deletedSessionId?.let { id ->
            backStack.removeAll { it is Chat && it.id == id }
            if (backStack.isEmpty()) goHome(null)
        }
    }
}

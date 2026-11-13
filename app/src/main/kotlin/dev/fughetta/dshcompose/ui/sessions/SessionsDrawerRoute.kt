package dev.fughetta.dshcompose.ui.sessions

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.domain.sessions.SessionCatalogState
import dev.fughetta.dshcompose.ui.*
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun SessionsDrawerRoute(
    state: AppState,
    source: StateFlow<SessionCatalogState>,
    drawerSource: StateFlow<DrawerUiState>,
    deletionSource: StateFlow<SessionDeletionState>,
    navigation: DrawerNavigation,
    onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalog by source.collectAsStateWithLifecycle()
    val drawerUi by drawerSource.collectAsStateWithLifecycle()
    val deletion by deletionSource.collectAsStateWithLifecycle()
    SessionsDrawerContent(state, catalog, drawerUi, deletion, navigation, onAction, modifier)
}

package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.sessions.DrawerUiState
import java.time.ZoneId

/** Projects the current workspace through host pins, local folders and status filters. */
fun AppState.drawerBlocks(
    catalog: SessionCatalogState,
    drawerUi: DrawerUiState,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<DrawerBlock>? {
    if (
        !drawerUi.loaded ||
            !workspaces.preferencesLoaded ||
            workspaces.loading && !workspaces.loaded ||
            catalog.loading
    )
        return null
    val workspace = workspaces.rows.firstOrNull { it.id == workspaces.selectedId }
    if (workspaces.selectedId != null && workspace == null) return null
    val members = workspace?.sessionIds
    val rows =
        catalog.sessions.filter {
            (members == null || it.sessionId in members) &&
                (it.sessionId in workspaces.archived) == workspaces.showArchived
        }
    return drawerBlocks(
        rows,
        workspaces.pinned,
        drawerUi.view,
        drawerUi.filter,
        if (connectionLive) sessionAttention.pending else emptyMap(),
        if (connectionLive) sessionAttention.unread else emptySet(),
        now = now,
        scoped = workspace != null,
        zone = zone,
    )
}

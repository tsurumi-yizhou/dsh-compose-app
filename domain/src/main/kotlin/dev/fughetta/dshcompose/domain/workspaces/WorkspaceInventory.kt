package dev.fughetta.dshcompose.domain.workspaces

import kotlinx.collections.immutable.*

data class WorkspaceInventory(
    val rows: PersistentList<Workspace> = persistentListOf(),
    val archived: PersistentSet<String> = persistentSetOf(),
    val pinned: PersistentList<String> = persistentListOf(),
)

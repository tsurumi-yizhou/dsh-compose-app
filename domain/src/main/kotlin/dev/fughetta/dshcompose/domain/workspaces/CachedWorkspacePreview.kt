package dev.fughetta.dshcompose.domain.workspaces

import kotlinx.collections.immutable.PersistentList

data class CachedWorkspacePreview(
    val path: String,
    val receipt: WorkspaceFileCopy,
    val missingFonts: PersistentList<String>,
)

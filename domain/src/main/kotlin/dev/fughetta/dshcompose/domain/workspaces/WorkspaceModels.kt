package dev.fughetta.dshcompose.domain.workspaces

import kotlinx.collections.immutable.*

/** One workspace DSH tracks, with the sessions it groups. */
data class Workspace(
    val id: String,
    val path: String,
    val title: String,
    val sessionIds: PersistentSet<String>,
    val updatedAt: String?,
)

data class WorkspaceFileEntry(val name: String, val type: String, val bytes: Long?)

data class WorkspaceFileListing(
    val path: String,
    val entries: PersistentList<WorkspaceFileEntry>,
    val truncated: Boolean,
)

data class WorkspaceTextPage(
    val version: String,
    val offset: Int,
    val count: Int,
    val eof: Boolean,
    val lines: PersistentList<String>,
)

sealed interface WorkspaceFileChange {
    data object Ready : WorkspaceFileChange

    data class Changed(val version: String?, val absent: Boolean) : WorkspaceFileChange
}

data class WorkspaceFileStat(val absolutePath: String, val version: String, val bytes: Long?)

data class WorkspaceFileCopy(val bytes: Long, val version: String)

data class DirectoryEntry(val name: String, val path: String, val hidden: Boolean)

data class DirectoryLevel(
    val path: String,
    val home: String,
    val crumbs: PersistentList<DirectoryEntry>,
    val entries: PersistentList<DirectoryEntry>,
    val truncated: Boolean,
) {
    val parent: DirectoryEntry?
        get() = crumbs.getOrNull(crumbs.size - 2)
}

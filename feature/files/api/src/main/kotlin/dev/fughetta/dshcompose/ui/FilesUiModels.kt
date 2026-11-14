package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.references.FileReferenceCandidate
import dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.workspaces.DirectoryEntry
import dev.fughetta.dshcompose.domain.workspaces.DirectoryLevel
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFileListing
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceTextPage
import kotlinx.collections.immutable.*

enum class SessionExportOperation {
    Share,
    Save,
}

data class SessionArchiveShare(val path: String, val name: String)

data class SessionExportUiState(
    val revision: Long = 0,
    val sessionId: String,
    val id: String,
    val supported: Boolean,
    val name: String,
    val busy: Boolean = false,
    val operation: SessionExportOperation? = null,
    val bytes: Long = 0,
    val total: Long? = null,
    val needsPermission: Boolean = false,
    val share: SessionArchiveShare? = null,
    val savedTo: String? = null,
    val shared: Boolean = false,
    val error: String? = null,
)

data class DirectoryPickerState(
    val nonce: Long,
    val level: DirectoryLevel? = null,
    val path: String = "",
    val query: String = "",
    val showHidden: Boolean = false,
    val entries: PersistentList<DirectoryEntry> = persistentListOf(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val newFolder: String? = null,
    val error: String? = null,
    val registerWorkspace: Boolean = false,
    val recent: PersistentList<String> = persistentListOf(),
) {
    val canChoose
        get() =
            !loading &&
                !busy &&
                error == null &&
                newFolder == null &&
                level != null &&
                path == level.path

    val canCreateFolder
        get() =
            newFolder?.let {
                it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\u0000' !in it
            } == true
}

data class WorkspaceSection(val kind: String, val name: String?, val rows: List<Workspace>)

data class WorkspaceFilesUiState(
    val sessionId: String,
    val path: String = "",
    val listing: WorkspaceFileListing? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val preview: WorkspaceFilePreview? = null,
    val watchError: String? = null,
    val search: WorkspaceFileSearch = WorkspaceFileSearch(),
)

data class ReplyLinkUiState(
    val id: Long,
    val sessionId: String,
    val target: String,
    val external: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

data class WorkspaceFileSearch(
    val query: String = "",
    val results: PersistentList<FileReferenceCandidate> = persistentListOf(),
    val loading: Boolean = false,
    val error: String? = null,
)

enum class WorkspacePreviewKind {
    Text,
    Image,
    Pdf,
    Office,
}

data class WorkspaceFilePreview(
    val path: String,
    val page: WorkspaceTextPage? = null,
    val previous: PersistentList<Int> = persistentListOf(),
    val loading: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
    val watchError: String? = null,
    val kind: WorkspacePreviewKind = WorkspacePreviewKind.Text,
    val cachePath: String? = null,
    val fileVersion: String? = null,
    val missingFonts: PersistentList<String> = persistentListOf(),
)

data class WorkspaceExportUiState(
    val name: String,
    val busy: Boolean = true,
    val bytes: Long = 0,
    val total: Long? = null,
    val saved: Boolean = false,
    val error: String? = null,
)

enum class ImageExportOperation {
    Save,
    Share,
}

data class ImageShareFile(val path: String, val mime: String, val name: String)

data class ImageExportUiState(
    val id: String,
    val operation: ImageExportOperation,
    val name: String? = null,
    val busy: Boolean = true,
    val needsPermission: Boolean = false,
    val share: ImageShareFile? = null,
    val saved: Boolean = false,
    val shared: Boolean = false,
    val error: String? = null,
)

enum class ReferenceKind {
    Files,
    Conversations,
}

data class ReferencePickerState(
    val sessionId: String,
    val draft: String,
    val start: Int,
    val end: Int,
    val kind: ReferenceKind = ReferenceKind.Files,
    val query: String = "",
    val loading: Boolean = false,
    val files: PersistentList<FileReferenceCandidate> = persistentListOf(),
    val sessions: PersistentList<SessionReferenceCandidate> = persistentListOf(),
    val error: String? = null,
    val inline: Boolean = false,
    val pathOnly: Boolean = false,
    val quoted: Boolean = false,
)

fun workspaceSections(
    rows: List<Workspace>,
    preferences: WorkspacePreferences,
): List<WorkspaceSection> {
    val pinned = mutableListOf<Workspace>()
    val groups = linkedMapOf<String, MutableList<Workspace>>()
    val rest = mutableListOf<Workspace>()
    for (row in rows) when {
        row.id in preferences.pinned -> pinned.add(row)
        row.id in preferences.groups ->
            groups.getOrPut(preferences.groups.getValue(row.id)) { mutableListOf() }.add(row)
        else -> rest.add(row)
    }
    return buildList {
        if (pinned.isNotEmpty()) add(WorkspaceSection("pinned", null, pinned))
        groups.forEach { (name, items) -> add(WorkspaceSection("group", name, items)) }
        if (rest.isNotEmpty()) add(WorkspaceSection("rest", null, rest))
    }
}

enum class WorkspaceOperation {
    Rename,
    Remove,
    Group,
}

data class WorkspacePreferences(
    val selected: String? = null,
    val pinned: PersistentSet<String> = persistentSetOf(),
    val groups: PersistentMap<String, String> = persistentMapOf(),
    val recent: PersistentList<String> = persistentListOf(),
) {
    fun grouped(id: String, group: String): WorkspacePreferences =
        copy(
            groups = if (group.isBlank()) groups.removing(id) else groups.putting(id, group.trim()),
        )

    fun remembered(path: String): WorkspacePreferences =
        copy(recent = persistentListOf(path).addingAll(recent.filter { it != path }.take(3)))

    fun forgotten(id: String): WorkspacePreferences =
        copy(
            selected = selected.takeUnless { it == id },
            pinned = pinned.removing(id),
            groups = groups.removing(id),
        )
}

data class WorkspaceDialogState(
    val id: String,
    val operation: WorkspaceOperation,
    val title: String,
    val busy: Boolean = false,
    val error: String? = null,
)

data class WorkspaceSessionDialogState(
    val sessionId: String,
    val operation: WorkspaceSessionOperation,
    val stopActivity: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val openAfterRestore: Boolean = false,
)

data class WorkspacesUiState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val loading: Boolean = false,
    /**
     * [rows], [archived] and [pinned] come from a completed read of this host; a reload keeps them.
     */
    val loaded: Boolean = false,
    val rows: PersistentList<Workspace> = persistentListOf(),
    val selectedId: String? = null,
    val showArchived: Boolean = false,
    val archived: PersistentSet<String> = persistentSetOf(),
    val pinned: PersistentList<String> = persistentListOf(),
    val dialog: WorkspaceDialogState? = null,
    val error: String? = null,
    val writing: Boolean = false,
    val sessionDialog: WorkspaceSessionDialogState? = null,
    val preferences: WorkspacePreferences = WorkspacePreferences(),
    val preferencesLoaded: Boolean = false,
    val preferencesError: String? = null,
)

package dev.fughetta.dshcompose.domain.workspaces

import dev.fughetta.dshcompose.domain.sessions.SessionsRepository

/**
 * What the directory chooser needs from one host: directory browsing and creation, workspace
 * registration and new-conversation creation. A value bundle (not a forwarding service): each
 * member is its own domain port.
 */
data class DirectoryPickerPorts(
    val files: WorkspaceFilesRepository,
    val workspaces: WorkspacesRepository,
    val sessions: SessionsRepository,
)

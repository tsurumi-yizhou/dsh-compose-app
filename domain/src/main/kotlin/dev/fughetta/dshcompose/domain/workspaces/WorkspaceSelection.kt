package dev.fughetta.dshcompose.domain.workspaces

import dev.fughetta.dshcompose.domain.references.*
import kotlinx.collections.immutable.*

data class WorkspaceReferenceMatches(
    val files: PersistentList<FileReferenceCandidate> = persistentListOf(),
    val sessions: PersistentList<SessionReferenceCandidate> = persistentListOf(),
)

sealed interface DirectorySelection {
    data class Registered(val workspace: Workspace) : DirectorySelection

    data class Conversation(val id: String) : DirectorySelection
}

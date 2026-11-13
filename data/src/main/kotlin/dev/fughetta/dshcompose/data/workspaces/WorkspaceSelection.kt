package dev.fughetta.dshcompose.data.workspaces

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.references.*
import dev.fughetta.dshcompose.domain.workspaces.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

suspend fun searchWorkspaceReferences(
    owner: WorkspaceFilesRepository,
    session: String,
    query: String,
    conversations: Boolean,
    immediate: Boolean,
): WorkspaceReferenceMatches {
    val generation = owner.generation
    if (!immediate) delay(200)
    if (owner.generation != generation) throw CancellationException("Retired reference search")
    return if (conversations)
        WorkspaceReferenceMatches(sessions = owner.sessionReferences(session, query))
    else WorkspaceReferenceMatches(files = owner.search(session, query))
}

suspend fun chooseWorkspaceDirectory(
    owner: DirectoryPickerPorts,
    path: String,
    register: Boolean,
    registerWorkspace: (suspend (WorkspacesRepository, String) -> Workspace)?,
): DirectorySelection {
    val generation = owner.files.generation ?: throw OperationFailure("request/stale")
    val result =
        if (register)
            DirectorySelection.Registered(
                registerWorkspace?.invoke(owner.workspaces, path) ?: owner.workspaces.create(path),
            )
        else DirectorySelection.Conversation(owner.sessions.create(cwd = path))
    if (owner.files.generation != generation) throw OperationFailure("request/stale")
    return result
}

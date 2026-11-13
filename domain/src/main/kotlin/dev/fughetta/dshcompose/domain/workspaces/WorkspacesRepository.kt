package dev.fughetta.dshcompose.domain.workspaces

import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.Flow

/** Workspace management for one host: the list stream plus the mutations the UI offers. */
interface WorkspacesRepository {
    val inventory: WorkspaceInventory

    suspend fun <T> changeCollection(
        items: List<T>,
        request: suspend (WorkspacesRepository, T) -> WorkspaceEvent,
        onApplied: () -> Unit,
    ): Boolean?

    suspend fun changeSession(
        id: String,
        operation: WorkspaceSessionOperation,
        stopActivity: Boolean,
    ): WorkspaceEvent

    suspend fun chooseDirectory(
        files: WorkspaceFilesRepository,
        sessions: dev.fughetta.dshcompose.domain.sessions.SessionsRepository,
        path: String,
        register: Boolean,
        registerWorkspace: (suspend (WorkspacesRepository, String) -> Workspace)?,
    ): DirectorySelection

    /** Opaque link token: changes when the engine connection is replaced. */
    val generation: Any?

    fun events(): Flow<WorkspaceEvent>

    suspend fun create(path: String): Workspace

    suspend fun rename(id: String, title: String): Workspace

    suspend fun delete(id: String)

    suspend fun move(id: String, beforeId: String?): PersistentList<String>

    suspend fun pin(sessionId: String, pinned: Boolean): PersistentList<String>

    suspend fun archive(sessionId: String, stopActivity: Boolean): List<String>

    suspend fun unarchive(sessionId: String): List<String>
}

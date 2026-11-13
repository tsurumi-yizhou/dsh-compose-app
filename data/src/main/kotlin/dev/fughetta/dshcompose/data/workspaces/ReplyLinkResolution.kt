package dev.fughetta.dshcompose.data.workspaces

import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import kotlinx.coroutines.CancellationException

/** Null selects the public website fallback; directories still open in the workspace browser. */
suspend fun resolveReplyFile(
    owner: WorkspaceFilesRepository,
    session: String,
    path: String,
): String? =
    try {
        owner.stat(session, path).absolutePath
    } catch (error: CancellationException) {
        throw error
    } catch (error: OperationFailure) {
        if (error.code == "workspace-file/not-regular-file") path else null
    } catch (_: Exception) {
        null
    }

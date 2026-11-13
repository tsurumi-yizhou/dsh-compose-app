package dev.fughetta.dshcompose.domain.permissions

import dev.fughetta.dshcompose.domain.commands.CommandResult

/** Permission presets of one host: the selection badges and the two writable choices. */
interface PermissionsRepository {
    suspend fun change(
        sessionId: String?,
        preset: String,
        defaults: PermissionDefaults?,
        stillCurrent: () -> Boolean,
    ): PermissionWriteback?

    suspend fun snapshot(sessionId: String?): PermissionSnapshot

    suspend fun setPreset(sessionId: String, preset: String): CommandResult

    suspend fun setDefault(preset: String, previous: PermissionDefaults)
}

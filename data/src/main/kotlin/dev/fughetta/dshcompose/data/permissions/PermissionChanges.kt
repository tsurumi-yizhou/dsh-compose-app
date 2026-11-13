package dev.fughetta.dshcompose.data.permissions

import dev.fughetta.dshcompose.domain.permissions.*
import kotlinx.coroutines.*

/** A successful write is confirmed only by the subsequent host snapshot. */
suspend fun changePermission(
    owner: PermissionsRepository,
    sessionId: String?,
    preset: String,
    defaults: PermissionDefaults?,
    stillCurrent: () -> Boolean,
): PermissionWriteback? {
    var applied = false
    try {
        applied =
            if (defaults != null) {
                owner.setDefault(preset, defaults)
                true
            } else owner.setPreset(requireNotNull(sessionId), preset).success
        if (!stillCurrent()) return null
        val snapshot = withTimeout(20000) { owner.snapshot(sessionId) }
        return PermissionWriteback(snapshot, applied)
    } catch (error: TimeoutCancellationException) {
        return PermissionWriteback(applied = applied, failure = error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return PermissionWriteback(applied = applied, failure = error)
    }
}

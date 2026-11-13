package dev.fughetta.dshcompose.engine.runtime

import dev.fughetta.dshcompose.domain.settings.PreferenceGroup
import java.io.File

/** Android product services supplied by the app; the engine owns the runtime lifecycle. */
interface RuntimeHost {
    val preferences: PreferenceGroup
    val serviceRunning: Boolean

    fun startService()

    fun requestStop()

    fun stopService()

    fun updateNotification()

    fun cancelStoppedAlert()

    fun postStoppedAlert()

    fun storageBinds(requested: Boolean, rootfs: File): List<String>

    fun prepareCapabilities(rootfs: File, log: (String) -> Unit): RuntimeCapabilities

    fun applyEnvironment(env: MutableMap<String, String>, manifest: RuntimeManifest)

    fun capabilitiesLaunched(requested: RuntimeCapabilities, mountedPatches: List<String>)

    fun engineReady()

    fun engineStopped()

    fun text(message: RuntimeMessage, vararg args: Any): String

    val listSeparator: String
}

/** A capability contributes launch overlays and agent instructions together. */
data class RuntimeCapabilities(
    val patches: List<String> = emptyList(),
    val instructions: String = "",
)

enum class RuntimeMessage {
    PACKAGE_CHECKSUM_FAILED,
    STORAGE_INSUFFICIENT,
    STOP_ENGINE_FIRST,
    NO_GOOD_CONFIG,
    CONFIG_MISSING,
    CONFIG_BUSY,
    PATCH_HAZARD,
    ENV_HAZARD,
    NORMAL_RECOVERED,
    PROBATION_FAILED,
    AUTO_STARTED,
}

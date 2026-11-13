package dev.fughetta.dshcompose.domain.presets

import kotlinx.coroutines.flow.StateFlow

/** One connection generation; retired instances never serve a replacement connection. */
interface PresetsRepository {
    val changes: StateFlow<Long>

    val supportsRemoval: Boolean

    suspend fun catalog(): AgentPresetCatalog

    suspend fun read(id: String): AgentPresetDocument

    suspend fun composition(id: String): PresetComposition

    suspend fun defaults(): AgentPresetDefaults?

    suspend fun setDefault(id: String, previous: AgentPresetDefaults)

    suspend fun select(sessionId: String, preset: String): String

    suspend fun render(
        composition: PresetComposition,
        enabled: Set<String>,
        prompt: String,
        complete: Boolean,
    ): String

    suspend fun packageFiles(bundle: PresetBundle): PresetPackageFiles

    suspend fun createSession(): String

    suspend fun prompt(sessionId: String, text: String, timeZone: String)

    suspend fun remove(name: String): PresetRemovalResult

    fun invalidate()
}

data class PresetInstallation(
    val name: String,
    val removable: Boolean,
    val official: Boolean,
    val optional: Boolean,
    val presetIds: Set<String>,
)

data class AgentPresetCatalog(
    val presets: List<AgentPreset>,
    val bundles: List<PresetInstallation>,
    val bundleError: Boolean,
)

data class PresetPackageFiles(
    val definition: PresetBundle,
    val directory: String,
    val files: String,
)

data class PresetRemovalResult(val restartRequired: Boolean)

fun agentPresetOwner(id: String, bundles: List<PresetInstallation>): PresetInstallation? =
    if (id in builtInAgentPresets) null
    else
        bundles
            .filter { it.removable && !it.official && !it.optional && id in it.presetIds }
            .singleOrNull()

class PresetOperationException(val code: String, cause: Throwable? = null) : Exception(code, cause)

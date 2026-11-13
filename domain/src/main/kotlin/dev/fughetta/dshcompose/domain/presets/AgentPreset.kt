package dev.fughetta.dshcompose.domain.presets

val builtInAgentPresets = setOf("standard", "ptc", "minimal", "cordis")

data class AgentPreset(
    val id: String,
    val isDefault: Boolean,
    val name: String?,
    val description: String?,
    val broken: String?,
    val order: Long?,
)

data class AgentPresetDocument(val id: String, val content: String)

data class AgentPresetDefaults(val revision: Long, val writable: Boolean)

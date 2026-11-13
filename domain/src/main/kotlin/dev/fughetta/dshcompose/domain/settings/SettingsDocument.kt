package dev.fughetta.dshcompose.domain.settings

import dev.fughetta.dshcompose.domain.chat.ToolPayload

/**
 * One namespace of the host's settings document. Values and [schema] are plain domain values
 * interpreted by the settings form renderer.
 */
data class SettingsNamespace(
    val ns: String,
    val revision: Long,
    val writable: Boolean,
    val autoGenerate: Boolean,
    val secrets: List<SettingsSecretSlot>,
    val value: ToolPayload.Obj?,
    val base: ToolPayload?,
    val user: ToolPayload?,
    val schema: SchemaNode,
)

data class SettingsDocument(
    val namespaces: List<SettingsNamespace>,
    val writable: Boolean,
    val hasDocument: Boolean,
)

/**
 * One mutation against a settings or plugin-config document. Never retained: it may hold a secret.
 */
data class SettingsOp(
    val path: List<String>,
    val value: ToolPayload? = null,
    val unset: Boolean = false,
) {
    override fun toString() = "SettingsOp(path=$path, unset=$unset)"
}

data class PluginConfigDocument(
    val entryId: String,
    val revision: String,
    val writable: Boolean,
    val secrets: List<SettingsSecretSlot>,
    val value: ToolPayload.Obj?,
    val base: ToolPayload?,
    val user: ToolPayload?,
    val schema: SchemaNode,
)

/** What the engine-settings screen needs from one host. */
data class EngineSettingsPorts(
    val settings: SettingsRepository,
    val plugins: dev.fughetta.dshcompose.domain.plugins.PluginsRepository,
)

package dev.fughetta.dshcompose.ui.engine_settings

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.plugins.PluginConfigEntrySummary
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.settings.SettingsSecretSlot
import kotlinx.collections.immutable.*

class EngineConfigDocument(
    val id: String,
    val schema: SchemaNode,
    val value: ToolPayload.Obj,
    val base: ToolPayload?,
    val user: ToolPayload?,
    val secrets: List<SettingsSecretSlot>,
    val writable: Boolean,
    val namespace: SettingsNamespace? = null,
    val plugin: PluginConfigDocument? = null,
) {
    companion object {
        fun settings(view: SettingsNamespace) =
            EngineConfigDocument(
                view.ns,
                view.schema,
                view.value ?: ToolPayload.Obj(emptyMap()),
                view.base,
                view.user,
                view.secrets,
                view.writable,
                namespace = view,
            )

        fun plugin(view: PluginConfigDocument) =
            EngineConfigDocument(
                view.entryId,
                view.schema,
                view.value ?: ToolPayload.Obj(emptyMap()),
                view.base,
                view.user,
                view.secrets,
                view.writable,
                plugin = view,
            )
    }
}

class EngineField(
    val path: List<String>,
    val node: SchemaNode,
    val depth: Int,
    val disabled: Boolean,
) {
    val key
        get() = path.joinToString("\u0000")

    fun title(locale: String) =
        node.meta.title?.resolve(locale)
            ?: node.effective.meta.title?.resolve(locale)
            ?: path.lastOrNull().orEmpty()

    fun description(locale: String) =
        node.meta.description?.resolve(locale) ?: node.effective.meta.description?.resolve(locale)
}

fun engineFields(document: EngineConfigDocument): PersistentList<EngineField> {
    val result = mutableListOf<EngineField>()
    fun walk(
        node: SchemaNode,
        path: List<String>,
        depth: Int,
        disabled: Boolean,
        ancestors: Set<SchemaNode>,
    ) {
        if (node.meta.hidden || node.credentialRef || depth > 32 || node in ancestors) return
        val locked = disabled || node.meta.disabled || node.effective.meta.disabled
        val fields = node.fields()
        if (fields != null && fields.isNotEmpty()) {
            for ((key, child) in fields) walk(
                child,
                path + key,
                depth + 1,
                locked,
                ancestors + node,
            )
        } else if (
            path.isNotEmpty() &&
                node.effective.type != "never" &&
                (node.effective.type != "const" || node.enumValues?.size != 1)
        )
            result.add(EngineField(path, node, depth, locked))
    }
    walk(document.schema, emptyList(), 0, !document.writable, emptySet())
    return result.toPersistentList()
}

data class EngineFieldEditor(val path: List<String>, val text: String, val error: String? = null)

data class EngineSettingsState(
    val revision: Long = 0,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val namespaces: PersistentList<SettingsNamespace> = persistentListOf(),
    val pluginEntries: PersistentList<PluginConfigEntrySummary> = persistentListOf(),
    val document: EngineConfigDocument? = null,
    val fields: PersistentList<EngineField> = persistentListOf(),
    val edits: PersistentMap<String, SettingsOp> = persistentMapOf(),
    val credentials: PersistentMap<String, CredentialStatus> = persistentMapOf(),
    val editor: EngineFieldEditor? = null,
    val secretPath: List<String>? = null,
    val scopeConfirmed: Boolean = false,
    val completeConfigError: String? = null,
    val writeUnconfirmed: Boolean = false,
    val confirmDiscard: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

sealed interface EngineSettingsAction {
    data class Observe(val value: Boolean) : EngineSettingsAction

    data object Refresh : EngineSettingsAction

    data class OpenNamespace(val ns: String) : EngineSettingsAction

    data class OpenPlugin(val entryId: String) : EngineSettingsAction

    data object Back : EngineSettingsAction

    data object Discard : EngineSettingsAction

    data object KeepEditing : EngineSettingsAction

    data class Set(val path: List<String>, val value: ToolPayload) : EngineSettingsAction

    data class Unset(val path: List<String>) : EngineSettingsAction

    data class Edit(val path: List<String>) : EngineSettingsAction

    data class EditorText(val value: String) : EngineSettingsAction

    data object SaveEditor : EngineSettingsAction

    data object CloseEditor : EngineSettingsAction

    data object Save : EngineSettingsAction

    data class Secret(val path: List<String>) : EngineSettingsAction

    class SaveSecret(val key: ProviderKey?) : EngineSettingsAction {
        override fun toString() = "SaveSecret(<redacted>)"
    }

    data object CloseSecret : EngineSettingsAction
}

fun EngineConfigDocument.credentialRef(path: List<String>): String? {
    if (plugin != null) return secrets.firstOrNull { it.path == path }?.credentialRef
    val parentPath = path.dropLast(1)
    for ((name, node) in schemaAt(schema, parentPath)?.fields().orEmpty()) {
        if (!node.credentialRef) continue
        val currentValue = lookupSetting(value, parentPath + name) as? ToolPayload.Text
        return currentValue?.value?.takeIf(String::isNotBlank)
            ?: node.effective.meta.default?.asText?.takeIf(String::isNotBlank)
    }
    return null
}

fun EngineSettingsState.fieldValue(path: List<String>): ToolPayload? {
    val document = document ?: return null
    var value: ToolPayload = document.value
    for (op in edits.values) value = replaceSetting(value, op.path, op.value, op.unset)
    return lookupSetting(value, path)
}

fun SchemaNode.inputNode(): SchemaNode {
    val node = effective
    if (node.type != "union") return node
    return node.list
        .map { it.effective }
        .filterNot { it.type == "const" && it.value == ToolPayload.Null }
        .singleOrNull() ?: node
}

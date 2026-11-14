package dev.fughetta.dshcompose.engine_settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.plugins.PluginBundle
import dev.fughetta.dshcompose.domain.plugins.PluginConfigEntrySummary
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.settings.EngineSettingsPorts
import dev.fughetta.dshcompose.ui.engine_settings.*
import dev.fughetta.dshcompose.ui.toUiJson
import dev.fughetta.dshcompose.ui.toUiPayload
import kotlinx.collections.immutable.*
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class EngineSettingsViewModel(parent: CoroutineScope? = null) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private val mutableState = MutableStateFlow(EngineSettingsState())
    val state = mutableState.asStateFlow()
    private var owner: EngineSettingsPorts? = null
    private var epoch = 0L
    private var readEpoch = 0L
    private var observing = false
    private var read: Job? = null
    private var mutation: Job? = null
    private var scopeConfirmed = false
    private var bundles = emptyList<PluginBundle>()
    private var dirty = false
    private var pendingPlugin: String? = null

    fun connect(client: EngineSettingsPorts?) {
        if (owner === client) return
        epoch++
        readEpoch++
        read?.cancel()
        mutation?.cancel()
        owner = client
        scopeConfirmed = false
        bundles = emptyList()
        pendingPlugin = null
        mutableState.value = EngineSettingsState(connected = client != null)
        if (observing) refresh()
    }

    fun hostEvent(name: String?) {
        if (
            name in
                setOf(
                    "settings/document-updated",
                    "plugin-manager/changed",
                    "credentials/reference-updated",
                    "credentials/record-updated",
                )
        ) {
            if (state.value.busy || state.value.edits.isNotEmpty()) dirty = true
            else if (observing) refresh()
        }
    }

    fun action(action: EngineSettingsAction) {
        when (action) {
            is EngineSettingsAction.Observe -> {
                observing = action.value
                if (observing) refresh()
                else {
                    readEpoch++
                    read?.cancel()
                }
            }
            EngineSettingsAction.Refresh ->
                if (state.value.edits.isNotEmpty()) update { it.copy(confirmDiscard = true) }
                else refresh()
            is EngineSettingsAction.OpenNamespace -> openNamespace(action.ns)
            is EngineSettingsAction.OpenPlugin -> {
                pendingPlugin = action.entryId
                if (state.value.edits.isNotEmpty()) update { it.copy(confirmDiscard = true) }
                else if (state.value.pluginEntries.isNotEmpty()) openPlugin(action.entryId)
                else if (observing) refresh()
            }
            EngineSettingsAction.Back ->
                if (state.value.edits.isNotEmpty()) update { it.copy(confirmDiscard = true) }
                else closeDocument()
            EngineSettingsAction.Discard -> {
                closeDocument()
                if (pendingPlugin != null) {
                    if (state.value.pluginEntries.isNotEmpty()) openPlugin(pendingPlugin!!)
                    else refresh()
                }
                if (dirty) {
                    dirty = false
                    refresh()
                }
            }
            EngineSettingsAction.KeepEditing -> update { it.copy(confirmDiscard = false) }
            is EngineSettingsAction.Set -> stage(action.path, action.value, false)
            is EngineSettingsAction.Unset -> stage(action.path, null, true)
            is EngineSettingsAction.Edit ->
                update { current ->
                    current.copy(
                        editor = EngineFieldEditor(action.path, editorText(current, action.path)),
                    )
                }
            is EngineSettingsAction.EditorText ->
                update { it.copy(editor = it.editor?.copy(text = action.value, error = null)) }
            EngineSettingsAction.SaveEditor -> saveEditor()
            EngineSettingsAction.CloseEditor -> update { it.copy(editor = null) }
            EngineSettingsAction.Save -> save()
            is EngineSettingsAction.Secret -> update { it.copy(secretPath = action.path) }
            is EngineSettingsAction.SaveSecret -> saveSecret(action.key)
            EngineSettingsAction.CloseSecret -> update { it.copy(secretPath = null) }
        }
    }

    private fun refresh() {
        val client =
            owner ?: return update { it.copy(loading = false, error = "ENGINE_NOT_CONNECTED") }
        if (!observing || state.value.busy) return
        val own = epoch
        val revision = ++readEpoch
        read?.cancel()
        scopeConfirmed = false
        update { it.copy(loading = true, error = null, scopeConfirmed = false) }
        read = scope.launch {
            try {
                val result = client.settings.inventory(client.plugins)
                if (!current(client, own) || revision != readEpoch) return@launch
                bundles = result.bundles
                scopeConfirmed = result.complete.isSuccess
                val entries =
                    result.complete
                        .getOrDefault(persistentListOf())
                        .filter(::ownedPlugin)
                        .toPersistentList()
                val selected = state.value.document
                update {
                    it.copy(
                        namespaces = result.description.namespaces.toPersistentList(),
                        pluginEntries = entries,
                        scopeConfirmed = scopeConfirmed,
                        loading = false,
                        error = null,
                        completeConfigError =
                            result.complete.exceptionOrNull()?.let { error ->
                                operationErrorCode(error) ?: "PLUGIN_CONFIG_READ_FAILED"
                            },
                    )
                }
                if (pendingPlugin != null && state.value.edits.isEmpty()) {
                    openPlugin(pendingPlugin!!)
                    return@launch
                }
                if (selected?.namespace != null && state.value.edits.isEmpty())
                    result.description.namespaces
                        .firstOrNull { it.ns == selected.id }
                        ?.let { accept(EngineConfigDocument.settings(it)) }
                if (selected?.plugin != null && state.value.edits.isEmpty()) openPlugin(selected.id)
            } catch (error: TimeoutCancellationException) {
                if (current(client, own) && revision == readEpoch)
                    update { it.copy(loading = false, error = "SETTINGS_TIMEOUT") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own) && revision == readEpoch)
                    update { it.copy(loading = false, error = code(error)) }
            }
        }
    }

    private fun ownedPlugin(entry: PluginConfigEntrySummary): Boolean {
        if (
            !scopeConfirmed ||
                entry.owner !in setOf("optional", "installed") ||
                entry.kind != "plugin"
        )
            return false
        val declaring =
            bundles
                .filter { it.enabled }
                .flatMap { bundle ->
                    bundle.rows.filter { it.rowId == entry.entryId }.map { bundle to it }
                }
        if (declaring.size != 1) return false
        val (bundle, row) = declaring.single()
        val module = row.moduleName
        val packageName =
            if (
                module.startsWith(".") ||
                    module.startsWith("/") ||
                    module.startsWith("file:") ||
                    module.startsWith("cordis:") ||
                    Regex("^[A-Za-z]:").containsMatchIn(module)
            )
                null
            else module.split('/').take(if (module.startsWith('@')) 2 else 1).joinToString("/")
        return (entry.packageName == null || packageName == entry.packageName) &&
            bundle.error == null &&
            ((bundle.official && bundle.optional) || bundle.installed && bundle.source != null) &&
            entry.bundle == bundle.name
    }

    private fun documentInScope(document: EngineConfigDocument): Boolean =
        document.plugin == null ||
            state.value.pluginEntries
                .firstOrNull { it.entryId == document.id }
                ?.let(::ownedPlugin) == true

    private fun openNamespace(ns: String) {
        if (state.value.busy || state.value.edits.isNotEmpty()) return
        state.value.namespaces
            .firstOrNull { it.ns == ns }
            ?.let { accept(EngineConfigDocument.settings(it)) }
    }

    private fun openPlugin(id: String) {
        val client = owner ?: return
        val summary =
            state.value.pluginEntries.firstOrNull { it.entryId == id && it.configurable }
                ?: run {
                    pendingPlugin = null
                    update { it.copy(error = "PLUGIN_CONFIG_UNAVAILABLE") }
                    return
                }
        pendingPlugin = null
        if (state.value.busy || state.value.edits.isNotEmpty() || !ownedPlugin(summary)) return
        val own = epoch
        val revision = ++readEpoch
        read?.cancel()
        update { it.copy(loading = true, error = null) }
        read = scope.launch {
            try {
                val detail = client.settings.readPluginConfig(id)
                if (current(client, own) && revision == readEpoch && ownedPlugin(summary)) {
                    accept(EngineConfigDocument.plugin(detail))
                    update { it.copy(loading = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own) && revision == readEpoch)
                    update { it.copy(loading = false, error = code(error)) }
            }
        }
    }

    private fun accept(document: EngineConfigDocument) {
        val fields = engineFields(document)
        update {
            it.copy(
                document = document,
                fields = fields,
                edits = persistentMapOf(),
                editor = null,
                error = null,
                notice = null,
                writeUnconfirmed = false,
            )
        }
        val client = owner ?: return
        val own = epoch
        val refs =
            fields
                .filter { it.node.secret }
                .mapNotNull { document.credentialRef(it.path) }
                .filter(::validCredentialRef)
                .toSet()
        if (refs.isNotEmpty())
            scope.launch {
                try {
                    val result = client.settings.credentials(refs).toPersistentMap()
                    if (current(client, own) && state.value.document === document)
                        update { it.copy(credentials = result) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (current(client, own) && state.value.document === document)
                        update { it.copy(credentials = persistentMapOf()) }
                }
            }
    }

    private fun stage(path: List<String>, value: ToolPayload?, unset: Boolean) {
        val document = state.value.document ?: return
        val field = state.value.fields.firstOrNull { it.path == path } ?: return
        if (
            !documentInScope(document) ||
                state.value.busy ||
                field.disabled ||
                !document.writable ||
                field.node.secret ||
                field.node.containsSecret()
        )
            return
        val op = SettingsOp(path, value, unset)
        update { it.copy(edits = it.edits.putting(field.key, op), error = null, editor = null) }
        if (
            document.namespace != null ||
                field.node.meta.volatile ||
                field.node.effective.meta.volatile
        )
            save()
    }

    private fun editorText(state: EngineSettingsState, path: List<String>): String {
        val value = state.fieldValue(path)
        return if (value is ToolPayload.Text) value.value
        else value?.toUiJson()?.toString().orEmpty()
    }

    private fun saveEditor() {
        val editor = state.value.editor ?: return
        val field = state.value.fields.firstOrNull { it.path == editor.path } ?: return
        val node = field.node.inputNode()
        val parsed =
            try {
                when (node.type) {
                    "string" -> ToolPayload.Text(editor.text)
                    "number" ->
                        ToolPayload.Num(
                            editor.text
                                .trim()
                                .toDouble()
                                .also { require(it.isFinite()) }
                                .toString(),
                        )
                    else -> Json.parseToJsonElement(editor.text).toUiPayload()
                }
            } catch (_: Exception) {
                return update { it.copy(editor = editor.copy(error = "INVALID_VALUE")) }
            }
        stage(editor.path, parsed, false)
    }

    private fun save() {
        val client = owner ?: return
        val document = state.value.document ?: return
        val ops = state.value.edits.values.toList()
        if (
            !documentInScope(document) ||
                state.value.busy ||
                state.value.writeUnconfirmed ||
                !document.writable ||
                ops.isEmpty()
        )
            return
        val own = epoch
        readEpoch++
        read?.cancel()
        update { it.copy(busy = true, error = null) }
        mutation = scope.launch {
            try {
                val next =
                    document.namespace?.let {
                        EngineConfigDocument.settings(client.settings.mutate(it, ops))
                    }
                        ?: document.plugin?.let {
                            EngineConfigDocument.plugin(
                                client.settings.writePluginConfig(
                                    it,
                                    ops,
                                ),
                            )
                        }
                        ?: return@launch
                if (current(client, own) && state.value.document === document) accept(next)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own))
                    update {
                        it.copy(
                            error = code(error),
                            notice = "READ_BEFORE_RETRY",
                            writeUnconfirmed = true,
                        )
                    }
            } finally {
                if (current(client, own)) update { it.copy(busy = false) }
            }
        }
    }

    private fun saveSecret(key: ProviderKey?) {
        val client = owner
        val document = state.value.document
        val path = state.value.secretPath
        if (client == null || document == null || path == null || state.value.busy) {
            key?.close()
            return
        }
        val node = schemaAt(document.schema, path)
        val ref = document.credentialRef(path)
        val slot = document.secrets.firstOrNull { it.path == path }
        if (
            !documentInScope(document) ||
                node?.secret != true ||
                !document.writable ||
                (document.plugin != null && slot?.writable != true) ||
                ref != null && state.value.credentials[ref]?.writable != true
        ) {
            key?.close()
            return
        }
        val own = epoch
        readEpoch++
        read?.cancel()
        update { it.copy(busy = true, secretPath = null, error = null) }
        mutation =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (ref != null) {
                        if (key == null) client.settings.clearProviderKey(ref)
                        else client.settings.setProviderKey(ref, key)
                    } else
                        document.namespace?.let {
                            client.settings.writeNamespaceSecret(it, path, key)
                        } ?: throw OperationFailure("SECRET_UNBOUND")
                    if (!current(client, own)) return@launch
                    val next =
                        if (document.plugin != null)
                            EngineConfigDocument.plugin(
                                client.settings.readPluginConfig(document.id),
                            )
                        else
                            client.settings
                                .describe()
                                .namespaces
                                .firstOrNull { it.ns == document.id }
                                ?.let(EngineConfigDocument::settings)
                    if (next != null && current(client, own)) accept(next)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (current(client, own)) update { it.copy(error = code(error)) }
                } finally {
                    key?.close()
                    if (current(client, own)) update { it.copy(busy = false) }
                }
            }
    }

    private fun closeDocument() {
        update {
            it.copy(
                document = null,
                fields = persistentListOf(),
                edits = persistentMapOf(),
                editor = null,
                secretPath = null,
                confirmDiscard = false,
                notice = null,
                writeUnconfirmed = false,
                credentials = persistentMapOf(),
            )
        }
    }

    private fun current(client: EngineSettingsPorts, own: Long) = owner === client && epoch == own

    private fun update(transform: (EngineSettingsState) -> EngineSettingsState) {
        mutableState.value = transform(state.value).copy(revision = state.value.revision + 1)
    }

    private fun code(error: Exception) = operationErrorCode(error) ?: "SETTINGS_FAILED"

    override fun onCleared() {
        close()
    }

    fun close() {
        epoch++
        readEpoch++
        read?.cancel()
        mutation?.cancel()
        owner = null
    }
}

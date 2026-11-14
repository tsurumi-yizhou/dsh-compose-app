package dev.fughetta.dshcompose.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AgentPresetsViewModel(
    private val repository: StateFlow<PresetsRepository?>,
    private val message: (Int) -> String,
    private val copyName: (String) -> String,
    private val requestText: (PresetPackageFiles, String?) -> String,
    private val openSession: (PresetsRepository, String) -> Unit,
) : ViewModel() {
    private val scope
        get() = viewModelScope

    private val mutable = MutableStateFlow(AgentPresetsUiState())
    val uiState = mutable.asStateFlow()
    private val state
        get() = mutable.value

    private var owner: PresetsRepository? = null
    private val setDefaultPreset = SetDefaultPresetUseCase()
    private val removePreset = RemovePresetUseCase()
    private val createPresetSession = CreatePresetSessionUseCase()
    private var epoch = 0L
    private var serial = 0L
    private var observing = false
    private var read: Job? = null
    private var document: Job? = null
    private var baseRead: Job? = null
    private var write: Job? = null
    private var readNumber = 0L

    init {
        scope.launch {
            repository.collectLatest { operations ->
                connect(operations)
                operations?.changes?.drop(1)?.collect { invalidate() }
            }
        }
    }

    fun handle(action: AppAction): Boolean {
        connect(repository.value)
        when (action) {
            is AppAction.ObserveAgentPresets -> {
                observe(action.observing)
                return true
            }
            AppAction.RefreshAgentPresets -> {
                refresh()
                return true
            }
            is AppAction.SetDefaultAgentPreset -> {
                setDefault(action.id)
                return true
            }
            is AppAction.ViewAgentPreset -> {
                view(action.id)
                return true
            }
            AppAction.CloseAgentPresetDetail -> {
                closeDetail()
                return true
            }
            AppAction.RetryAgentPresetDocument -> {
                retryDocument()
                return true
            }
            is AppAction.OpenPresetEditor -> {
                edit(action.mode, action.id)
                return true
            }
            is AppAction.EditPresetField -> {
                field(action.field, action.value)
                return true
            }
            is AppAction.ChoosePresetBase -> {
                base(action.id)
                return true
            }
            is AppAction.TogglePresetRow -> {
                toggle(action.id, action.enabled)
                return true
            }
            is AppAction.ReplacePresetPrompt -> {
                complete(action.complete)
                return true
            }
            AppAction.RetryPresetBase -> {
                retryBase()
                return true
            }
            AppAction.SubmitPreset -> {
                submit()
                return true
            }
            AppAction.ClosePresetEditor -> {
                closeEditor()
                return true
            }
            AppAction.DiscardPresetEditor -> {
                discardEditor()
                return true
            }
            AppAction.KeepPresetEditor -> {
                keepEditor()
                return true
            }
            AppAction.OpenPresetCreatorSession -> {
                openCreated()
                return true
            }
            is AppAction.RemoveAgentPreset -> {
                remove(action.id)
                return true
            }
            AppAction.ConfirmRemoveAgentPreset -> {
                confirmRemoval()
                return true
            }
            AppAction.CloseAgentPresetRemoval -> {
                closeRemoval()
                return true
            }
            else -> return false
        }
    }

    private fun current(client: PresetsRepository, own: Long) =
        owner === client && own == epoch && repository.value === client

    private fun adopt(value: AgentPresetCatalog) {
        update(
            state.copy(
                presets = value.presets.toPersistentList(),
                bundles = value.bundles,
                bundlesError =
                    if (value.bundleError) message(R.string.preset_bundles_failed) else null,
                loading = false,
            ),
        )
    }

    private fun connect(client: PresetsRepository?) {
        if (owner === client) return
        epoch++
        readNumber++
        read?.cancel()
        document?.cancel()
        baseRead?.cancel()
        write?.cancel()
        owner = client
        update(
            AgentPresetsUiState(
                connected = client != null,
                supportsRemoval = client?.supportsRemoval == true,
            ),
        )
        if (observing) refresh()
    }

    private fun observe(value: Boolean) {
        observing = value
        if (value) refresh()
        else {
            readNumber++
            read?.cancel()
            update(state.copy(loading = false))
        }
    }

    private fun invalidate() {
        if (observing && !state.writing) refresh()
    }

    private fun refresh() {
        val client = owner ?: return
        if (!state.connected || state.writing) return
        val own = epoch
        val number = ++readNumber
        read?.cancel()
        update(state.copy(loading = true, error = null))
        read = scope.launch {
            try {
                val result = client.catalog()
                if (current(client, own) && number == readNumber) adopt(result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && number == readNumber)
                    update(
                        state.copy(
                            loading = false,
                            error = message(R.string.presets_read_failed),
                        ),
                    )
            }
        }
    }

    private fun setDefault(id: String) {
        val preset =
            state.presets.firstOrNull { it.id == id && it.broken == null && !it.isDefault }
                ?: return
        val client = owner ?: return
        if (!state.connected || state.writing || state.loading) return
        val own = epoch
        readNumber++
        read?.cancel()
        update(state.copy(writing = true, loading = false, error = null, notice = null))
        write = scope.launch {
            try {
                val confirmed = setDefaultPreset(client, preset.id)
                if (current(client, own)) {
                    adopt(confirmed)
                    update(state.copy(notice = message(R.string.preset_default_confirmed)))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own))
                    update(state.copy(error = message(R.string.preset_default_failed)))
            } finally {
                if (current(client, own)) update(state.copy(writing = false))
            }
        }
    }

    private fun view(id: String) {
        val preset = state.presets.firstOrNull { it.id == id } ?: return
        val client = owner ?: return
        if (!state.connected || state.writing) return
        val own = epoch
        val nonce = ++serial
        document?.cancel()
        update(state.copy(detail = AgentPresetDetail(nonce, preset)))
        document = scope.launch {
            try {
                val value = client.read(id)
                if (current(client, own) && state.detail?.nonce == nonce)
                    update(
                        state.copy(
                            detail = AgentPresetDetail(nonce, preset, value, loading = false),
                        ),
                    )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && state.detail?.nonce == nonce)
                    update(
                        state.copy(
                            detail =
                                state.detail?.copy(
                                    loading = false,
                                    error = message(R.string.preset_document_failed),
                                ),
                        ),
                    )
            }
        }
    }

    private fun closeDetail() {
        document?.cancel()
        update(state.copy(detail = null))
    }

    private fun retryDocument() {
        state.detail?.let { view(it.preset.id) }
    }

    private fun name(preset: AgentPreset) =
        presetNameResource(preset.id)?.let(message)
            ?: preset.name?.takeIf(String::isNotBlank)
            ?: preset.id

    private fun edit(mode: PresetEditMode, id: String?) {
        if (!state.connected || state.writing || !state.creatorAvailable || state.editor != null)
            return
        val source = id?.let { key -> state.presets.firstOrNull { it.id == key } }
        if (mode != PresetEditMode.Create && source == null) return
        val bundle =
            if (mode == PresetEditMode.Edit)
                source?.let { agentPresetOwner(it.id, state.bundles) } ?: return
            else null
        val values =
            PresetEditorValues(
                name =
                    when (mode) {
                        PresetEditMode.Create -> ""
                        PresetEditMode.Duplicate -> copyName(name(source!!))
                        PresetEditMode.Edit -> name(source!!)
                    },
                id =
                    when (mode) {
                        PresetEditMode.Create -> ""
                        PresetEditMode.Duplicate ->
                            freePresetId("${source!!.id}-copy", state.presets.map { it.id }.toSet())
                        PresetEditMode.Edit -> source!!.id
                    },
                description = source?.description.orEmpty(),
                base = source?.id ?: "standard",
            )
        update(state.copy(editor = PresetEditorState(++serial, mode, source, bundle, values)))
        loadBase(first = true)
    }

    private fun field(field: PresetField, text: String) {
        val editor = state.editor?.takeIf { !it.busy && it.createdSessionId == null } ?: return
        var values = editor.values
        fun limited(limit: Int) =
            text.substring(
                0,
                text.offsetByCodePoints(0, minOf(limit, text.codePointCount(0, text.length))),
            )
        when (field) {
            PresetField.Name -> {
                values = values.copy(name = limited(40))
                if (!editor.idTouched && editor.mode != PresetEditMode.Edit) {
                    val slug = presetIdFromName(values.name)
                    values =
                        values.copy(
                            id =
                                if (slug.isEmpty()) ""
                                else freePresetId(slug, state.presets.map { it.id }.toSet()),
                        )
                }
            }
            PresetField.Id ->
                if (editor.mode != PresetEditMode.Edit) values = values.copy(id = limited(40))
                else return
            PresetField.Description -> values = values.copy(description = limited(120))
            PresetField.Prompt ->
                if (!editor.loading && editor.composition?.hasPersona == true)
                    values = values.copy(prompt = text)
                else return
        }
        update(
            state.copy(
                editor =
                    editor.copy(
                        values = values,
                        idTouched = editor.idTouched || field == PresetField.Id,
                        error = null,
                    ),
            ),
        )
    }

    private fun base(id: String) {
        val editor =
            state.editor?.takeIf {
                it.mode == PresetEditMode.Create && !it.busy && it.createdSessionId == null
            } ?: return
        if (editor.values.base == id || state.presets.none { it.id == id && it.broken == null })
            return
        update(
            state.copy(
                editor =
                    editor.copy(
                        values = editor.values.copy(base = id),
                        composition = null,
                        loading = true,
                        error = null,
                    ),
            ),
        )
        loadBase(first = !editor.initialCompositionLoaded && id == editor.initial.base)
    }

    private fun retryBase() {
        state.editor
            ?.takeIf { !it.busy && it.createdSessionId == null }
            ?.let {
                loadBase(first = !it.initialCompositionLoaded && it.values.base == it.initial.base)
            }
    }

    private fun loadBase(first: Boolean) {
        val editor = state.editor ?: return
        val client = owner ?: return
        val own = epoch
        val id = editor.values.base
        baseRead?.cancel()
        update(state.copy(editor = editor.copy(loading = true, error = null)))
        baseRead = scope.launch {
            try {
                val composition = client.composition(id)
                val present =
                    state.editor?.takeIf { it.nonce == editor.nonce && it.values.base == id }
                        ?: return@launch
                if (!current(client, own)) return@launch
                fun loaded(value: PresetEditorValues) =
                    value.copy(
                        prompt = composition.prompt,
                        complete = composition.complete,
                        enabled = composition.enabled.toPersistentSet(),
                    )
                update(
                    state.copy(
                        editor =
                            present.copy(
                                composition = composition,
                                loading = false,
                                values = loaded(present.values),
                                initial = if (first) loaded(present.initial) else present.initial,
                                initialCompositionLoaded =
                                    present.initialCompositionLoaded || first,
                            ),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (
                    current(client, own) &&
                        state.editor?.nonce == editor.nonce &&
                        state.editor?.values?.base == id
                )
                    update(
                        state.copy(
                            editor =
                                state.editor?.copy(
                                    loading = false,
                                    error = message(R.string.preset_document_failed),
                                ),
                        ),
                    )
            }
        }
    }

    private fun toggle(id: String, enabled: Boolean) {
        val editor =
            state.editor?.takeIf { !it.busy && !it.loading && it.createdSessionId == null }
                ?: return
        if (editor.composition?.switchable?.none { it.id == id } != false) return
        update(
            state.copy(
                editor =
                    editor.copy(
                        values =
                            editor.values.copy(
                                enabled =
                                    if (enabled) editor.values.enabled.adding(id)
                                    else editor.values.enabled.removing(id),
                            ),
                    ),
            ),
        )
    }

    private fun complete(value: Boolean) {
        val editor =
            state.editor?.takeIf {
                !it.busy &&
                    !it.loading &&
                    it.createdSessionId == null &&
                    it.composition?.hasPersona == true
            } ?: return
        update(state.copy(editor = editor.copy(values = editor.values.copy(complete = value))))
    }

    private fun closeEditor() {
        val editor = state.editor?.takeIf { !it.busy } ?: return
        if (editor.dirty && editor.createdSessionId == null)
            update(state.copy(editor = editor.copy(discard = true)))
        else discardEditor()
    }

    private fun keepEditor() {
        state.editor?.let { update(state.copy(editor = it.copy(discard = false))) }
    }

    private fun discardEditor() {
        if (state.editor?.busy == true) return
        baseRead?.cancel()
        update(state.copy(editor = null))
    }

    private fun submit() {
        val editor =
            state.editor?.takeIf { !it.busy && !it.loading && it.createdSessionId == null }
                ?: return
        val composition = editor.composition ?: return
        val client = owner ?: return
        if (!state.connected || state.writing || !state.creatorAvailable) return
        val values = editor.values
        if (
            values.name.isBlank() ||
                !validPresetId(values.id) ||
                editor.mode != PresetEditMode.Edit && state.presets.any { it.id == values.id }
        ) {
            update(
                state.copy(editor = editor.copy(error = message(R.string.preset_fields_invalid))),
            )
            return
        }
        if (
            state.presets.none { it.id == values.base } ||
                editor.mode == PresetEditMode.Edit &&
                    (editor.source?.id?.let { agentPresetOwner(it, state.bundles)?.name } !=
                        editor.bundle?.name)
        ) {
            update(state.copy(editor = editor.copy(error = message(R.string.preset_changed))))
            return
        }
        val own = epoch
        readNumber++
        read?.cancel()
        update(
            state.copy(
                writing = true,
                loading = false,
                editor = editor.copy(busy = true, error = null),
            ),
        )
        write = scope.launch {
            var created: String? = null
            try {
                val text =
                    withContext(Dispatchers.Default) {
                        val bundle =
                            PresetBundle(
                                values.id.trim(),
                                values.name.trim(),
                                values.description.trim(),
                                client.render(
                                    composition,
                                    values.enabled,
                                    if (values.prompt == composition.prompt) composition.prompt
                                    else values.prompt.trim(),
                                    values.complete,
                                ),
                                if (editor.mode == PresetEditMode.Edit) editor.source?.order
                                else
                                    (state.presets.mapNotNull { it.order }.maxOrNull()?.plus(1)
                                        ?: 10),
                                editor.bundle?.name,
                            )
                        val files = client.packageFiles(bundle)
                        requestText(files, editor.bundle?.name) + files.files
                    }
                val result = createPresetSession(client, text, java.time.ZoneId.systemDefault().id)
                created = result.sessionId
                if (!current(client, own)) return@launch
                update(state.copy(editor = state.editor?.copy(createdSessionId = created)))
                result.error?.let { throw PresetOperationException(it) }
                update(state.copy(editor = null))
                openSession(client, created)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && state.editor?.nonce == editor.nonce)
                    update(
                        state.copy(
                            editor =
                                state.editor?.copy(
                                    busy = false,
                                    error =
                                        message(
                                            if (created == null) R.string.preset_create_failed
                                            else R.string.preset_handoff_unconfirmed,
                                        ),
                                ),
                        ),
                    )
            } finally {
                if (current(client, own)) update(state.copy(writing = false))
            }
        }
    }

    private fun openCreated() {
        val editor = state.editor?.takeIf { !it.busy } ?: return
        val id = editor.createdSessionId ?: return
        val client = owner ?: return
        if (!current(client, epoch)) return
        update(state.copy(editor = null))
        openSession(client, id)
    }

    private fun remove(id: String) {
        if (!state.connected || state.writing || !state.supportsRemoval || state.loading) return
        val preset = state.presets.firstOrNull { it.id == id && !it.isDefault } ?: return
        val bundle = agentPresetOwner(id, state.bundles) ?: return
        update(state.copy(removal = AgentPresetRemoval(preset, bundle)))
    }

    private fun closeRemoval() {
        if (!state.writing) update(state.copy(removal = null))
    }

    private fun confirmRemoval() {
        val dialog = state.removal?.takeIf { !it.busy } ?: return
        val client = owner ?: return
        if (!state.connected || state.writing || !state.supportsRemoval) return
        val own = epoch
        readNumber++
        read?.cancel()
        update(
            state.copy(
                writing = true,
                loading = false,
                removal = dialog.copy(busy = true, error = null),
                notice = null,
            ),
        )
        write = scope.launch {
            try {
                val result = removePreset(client, dialog.preset.id, dialog.bundle.name)
                if (!current(client, own)) return@launch
                val confirmed = result.catalog
                if (current(client, own)) {
                    adopt(confirmed)
                    update(
                        state.copy(
                            removal = null,
                            notice =
                                message(
                                    if (result.restartRequired) R.string.preset_restart_required
                                    else R.string.preset_removal_confirmed,
                                ),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own))
                    update(
                        state.copy(
                            removal = dialog.copy(error = message(R.string.preset_remove_failed)),
                        ),
                    )
            } finally {
                if (current(client, own)) update(state.copy(writing = false))
            }
        }
    }

    private fun update(next: AgentPresetsUiState) {
        mutable.value = next
    }

    override fun onCleared() {
        epoch++
        read?.cancel()
        document?.cancel()
        baseRead?.cancel()
        write?.cancel()
        owner = null
    }
}

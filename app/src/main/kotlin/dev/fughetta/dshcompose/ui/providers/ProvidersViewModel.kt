package dev.fughetta.dshcompose.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns only reference names and credential status. Secret ownership lasts for one request. */
class ProvidersViewModel(private val repository: StateFlow<ProvidersRepository?>) : ViewModel() {
    private val scope
        get() = viewModelScope

    private val mutable = MutableStateFlow(ProvidersUiState())
    val uiState = mutable.asStateFlow()
    private val state
        get() = mutable.value

    private val saveProfile = SaveProviderProfileUseCase()
    private var owner: ProvidersRepository? = null
    private var connection = 0L
    private var observing = false
    private var read: Job? = null
    private var readNumber = 0L
    private var refreshAfterWrite = false
    private var write: Job? = null
    private var discovery: Job? = null
    private var discoveryNumber = 0L
    private var catalogRead: Job? = null
    private var catalogNumber = 0L

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
            is AppAction.ObserveProviders -> {
                observe(action.observing)
                return true
            }
            AppAction.RefreshProviders -> {
                refresh()
                return true
            }
            is AppAction.ManageProviderKey -> {
                open(action.account, action.removing)
                return true
            }
            is AppAction.SubmitProviderKey -> {
                submit(action.key)
                return true
            }
            AppAction.ConfirmRemoveProviderKey -> {
                submit(null)
                return true
            }
            AppAction.CloseProviderKeyDialog -> {
                dismiss()
                return true
            }
            AppAction.OpenMagpieProvider -> {
                openEndpoint("magpie", "Magpie", "")
                return true
            }
            AppAction.OpenDeepSeekProvider -> {
                openEndpoint("deepseek-api", "DeepSeek", "https://api.deepseek.com/v1")
                return true
            }
            is AppAction.SaveProviderEndpoint -> {
                saveEndpoint(action.key)
                return true
            }
            is AppAction.OpenProviderEditor -> {
                editProfile(action.route, action.removing)
                return true
            }
            is AppAction.EditProviderField -> {
                edit(action.field, action.value)
                return true
            }
            is AppAction.ChangeProviderApi -> {
                protocol(action.api)
                return true
            }
            AppAction.SaveProviderProfile -> {
                saveProfile()
                return true
            }
            AppAction.CloseProviderEditor -> {
                dismissProfile()
                return true
            }
            AppAction.DiscardProviderEditsAndRefresh -> {
                dismissProfile(reload = true)
                return true
            }
            AppAction.OpenProviderDiscovery -> {
                openDiscovery()
                return true
            }
            is AppAction.SubmitProviderDiscovery -> {
                discover(action.key)
                return true
            }
            AppAction.CloseProviderDiscovery -> {
                closeDiscovery()
                return true
            }
            is AppAction.FilterDiscoveredModels -> {
                filterDiscovery(action.query)
                return true
            }
            is AppAction.ToggleDiscoveredModel -> {
                toggleDiscovered(action.id)
                return true
            }
            AppAction.SelectAllDiscoveredModels -> {
                selectDiscovered()
                return true
            }
            AppAction.ImportDiscoveredModels -> {
                importDiscovered()
                return true
            }
            is AppAction.OpenModelParameters -> {
                openModelParameters(action.id)
                return true
            }
            AppAction.ReloadProviderCatalog -> {
                loadCatalog()
                return true
            }
            is AppAction.EditModelParameter -> {
                modelParameter(action.field, action.value)
                return true
            }
            is AppAction.ChangeModelImages -> {
                modelImages(action.enabled)
                return true
            }
            is AppAction.ChangeModelThinking -> {
                modelThinking(action.enabled)
                return true
            }
            is AppAction.ToggleModelEffort -> {
                modelEffort(action.effort)
                return true
            }
            AppAction.ApplyModelParameters -> {
                applyModelParameters()
                return true
            }
            AppAction.CloseModelParameters -> {
                closeModelParameters()
                return true
            }
            is AppAction.OpenCatalogContext -> {
                openCatalogContext(action.id, action.name)
                return true
            }
            is AppAction.EditCatalogContext -> {
                editCatalogContext(action.value)
                return true
            }
            AppAction.ApplyCatalogContext -> {
                applyCatalogContext()
                return true
            }
            AppAction.CloseCatalogContext -> {
                closeCatalogContext()
                return true
            }
            else -> return false
        }
    }

    private fun connect(client: ProvidersRepository?) {
        if (owner === client) return
        connection++
        readNumber++
        refreshAfterWrite = false
        read?.cancel()
        write?.cancel()
        retireDiscovery()
        retireCatalog()
        owner = client
        update(ProvidersUiState(connected = client != null))
        if (observing) refresh()
    }

    private fun observe(value: Boolean) {
        observing = value
        if (value) refresh()
        else {
            readNumber++
            read?.cancel()
            retireDiscovery()
            retireCatalog()
            update(
                state.copy(
                    loading = false,
                    editor = state.editor?.copy(discovery = null, catalogLoading = false),
                ),
            )
        }
    }

    private fun invalidate() {
        if (observing) refresh()
    }

    private fun refresh() {
        if (write?.isActive == true) {
            refreshAfterWrite = true
            return
        }
        val client = owner ?: return
        val own = connection
        val number = ++readNumber
        read?.cancel()
        read = scope.launch {
            update(state.copy(loading = true, error = null))
            try {
                load(client, own, number)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && readNumber == number)
                    update(state.copy(error = "providers/providers_load_failed"))
            } finally {
                if (current(client, own) && readNumber == number)
                    update(state.copy(loading = false))
            }
        }
    }

    private suspend fun load(client: ProvidersRepository, own: Long, number: Long? = null) {
        val snapshot = client.refresh()
        if (current(client, own) && (number == null || number == readNumber))
            update(
                state.copy(
                    settings = snapshot.settings,
                    accounts = snapshot.accounts,
                    credentials = snapshot.credentials,
                    error = null,
                ),
            )
    }

    private fun open(account: ProviderAccount, removing: Boolean) {
        if (state.writing || !state.connected) return
        val live = state.accounts.firstOrNull { it.id == account.id } ?: return
        val ref = live.credentialRef ?: return
        val status = state.credentials[ref] ?: return
        if (!status.writable || removing && !status.configured) return
        update(state.copy(dialog = CredentialDialogState(ref, live.name, removing), notice = null))
    }

    private fun dismiss() {
        if (!state.writing) update(state.copy(dialog = null))
    }

    private fun submit(key: ProviderKey?) {
        val dialog = state.dialog
        val client = owner
        if (
            dialog == null ||
                state.writing ||
                client == null ||
                !state.connected ||
                dialog.removing == (key != null)
        ) {
            key?.close()
            return
        }
        val own = connection
        if (!current(client, own)) {
            key?.close()
            return
        }
        readNumber++
        read?.cancel()
        update(
            state.copy(
                dialog = dialog.copy(busy = true, error = null),
                writing = true,
                loading = false,
                notice = null,
            ),
        )
        write =
            scope
                .launch {
                    var acknowledged = false
                    try {
                        client.setCredential(dialog.ref, key)
                        acknowledged = true
                        if (!current(client, own)) return@launch
                        refreshAfterWrite = false
                        load(client, own)
                        if (current(client, own))
                            update(
                                state.copy(
                                    dialog = null,
                                    notice = "providers/credential_updated",
                                ),
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (current(client, own)) {
                            if (acknowledged)
                                update(
                                    state.copy(
                                        dialog = null,
                                        error = "providers/credential_status_unconfirmed",
                                    ),
                                )
                            else
                                update(
                                    state.copy(
                                        dialog =
                                            dialog.copy(
                                                busy = false,
                                                error = "providers/credential_write_failed",
                                            ),
                                    ),
                                )
                        }
                    }
                }
                .also { job ->
                    job.invokeOnCompletion {
                        key?.close()
                        scope.launch {
                            if (current(client, own)) update(state.copy(writing = false))
                            if (current(client, own) && observing && refreshAfterWrite) {
                                refreshAfterWrite = false
                                refresh()
                            }
                        }
                    }
                }
    }

    private fun openEndpoint(route: String, name: String, defaultUrl: String) {
        if (!state.connected || state.writing) return
        val settings = state.settings?.takeIf { it.writable } ?: return
        val profile = settings.find(route)
        retireDiscovery()
        retireCatalog()
        update(
            state.copy(
                editor =
                    ProviderEditorState(
                        revision = settings.revision,
                        original = profile,
                        route = route,
                        name = name,
                        baseUrl = profile?.baseUrl ?: defaultUrl,
                        credentialRef =
                            profile?.credentialRef
                                ?: if (route == "deepseek-api") "DEEPSEEK_API_KEY"
                                else deriveProviderKeyRef(route),
                        modelIds = profile?.models?.joinToString("\n") { it.id }.orEmpty(),
                        importedModels =
                            profile?.models?.associateBy { it.id }?.toPersistentMap()
                                ?: persistentMapOf(),
                        endpointOnly = true,
                    ),
            ),
        )
    }

    private fun saveEndpoint(key: ProviderKey?) {
        val editor =
            state.editor?.let {
                it.copy(
                    credentialRef =
                        it.credentialRef.trim().ifBlank { deriveProviderKeyRef(it.route) },
                )
            }
        val client = owner
        val settings = state.settings
        if (
            editor == null ||
                (!editor.endpointOnly && !editor.full) ||
                editor.removing ||
                !validProviderRoute(editor.route) ||
                (editor.credentialRef.isNotBlank() &&
                    !validCredentialRef(editor.credentialRef.trim())) ||
                editor.busy ||
                state.writing ||
                client == null ||
                settings?.writable != true ||
                !validProviderBaseUrl(editor.endpointUrl)
        ) {
            key?.close()
            return
        }
        val own = connection
        if (!current(client, own)) {
            key?.close()
            return
        }
        val url = editor.endpointUrl
        val effectiveKey = key ?: if (editor.route == "magpie") ProviderKey.from("magpie") else null
        val stored =
            editor.original?.takeIf {
                it.baseUrl == url &&
                    it.api == editor.api &&
                    it.credentialRef == editor.credentialRef
            }
        if (
            effectiveKey == null &&
                (editor.endpointNeedsKey || editor.original != null && stored == null)
        ) {
            update(state.copy(editor = editor.copy(error = "providers/endpoint-key-required")))
            return
        }
        readNumber++
        read?.cancel()
        update(
            state.copy(
                writing = true,
                loading = false,
                editor = editor.copy(busy = true, error = null, endpointSaved = false),
            ),
        )
        write =
            scope
                .launch {
                    var failure = "providers/provider_discovery_failed"
                    try {
                        val discovered =
                            if (effectiveKey == null && stored != null)
                                client.discover(stored.route)
                            else client.discover(url, editor.api, effectiveKey)
                        if (discovered.isEmpty())
                            throw ProviderOperationException("providers/empty-models")
                        val models =
                            discovered
                                .distinctBy { it.id }
                                .map { found ->
                                    editor.editedModels[found.id]
                                        ?: editor.original?.models?.firstOrNull {
                                            it.id == found.id
                                        }
                                        ?: found.toSpec()
                                }
                        failure = "providers/provider_profile_write_failed"
                        val result =
                            saveProfile(
                                client,
                                settings,
                                ProviderProfileDraft(
                                    editor.revision,
                                    editor.original,
                                    editor.route,
                                    editor.name,
                                    editor.api,
                                    url,
                                    editor.credentialRef,
                                    models,
                                    editor.resolvedOverrides(),
                                    false,
                                ),
                            )
                        if (!current(client, own)) return@launch
                        val saved =
                            result.find(editor.route)
                                ?: throw ProviderOperationException("providers/invalid-profile")
                        update(
                            state.copy(
                                settings = result,
                                accounts = result.accounts(),
                                editor =
                                    editor.copy(
                                        revision = result.revision,
                                        original = saved,
                                        baseUrl = url,
                                        busy = true,
                                        endpointNeedsKey = effectiveKey != null,
                                        modelIds = models.joinToString("\n") { it.id },
                                        importedModels =
                                            models.associateBy { it.id }.toPersistentMap(),
                                    ),
                            ),
                        )
                        failure = "providers/credential_write_failed"
                        if (effectiveKey != null)
                            client.setCredential(editor.credentialRef, effectiveKey)
                        if (!current(client, own)) return@launch
                        update(
                            state.copy(
                                editor =
                                    state.editor?.copy(
                                        endpointSaved = true,
                                        endpointNeedsKey = false,
                                    ),
                            ),
                        )
                        failure = "providers/provider_profile_status_unconfirmed"
                        load(client, own)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (current(client, own))
                            update(
                                state.copy(
                                    editor =
                                        state.editor?.copy(
                                            error =
                                                if (
                                                    error is ProviderOperationException &&
                                                        error.code == "settings/conflict"
                                                )
                                                    "providers/provider_profile_conflict"
                                                else failure,
                                        ),
                                ),
                            )
                    } finally {
                        effectiveKey?.close()
                        if (current(client, own)) {
                            update(
                                state.copy(
                                    writing = false,
                                    editor = state.editor?.copy(busy = false),
                                ),
                            )
                            if (observing && refreshAfterWrite) {
                                refreshAfterWrite = false
                                refresh()
                            }
                        }
                    }
                }
                .also { job -> job.invokeOnCompletion { effectiveKey?.close() } }
    }

    private fun editProfile(route: String?, removing: Boolean = false) {
        if (!state.connected || state.writing) return
        val settings = state.settings?.takeIf { it.writable } ?: return
        val profile = route?.let { settings.find(it) ?: return }
        if (removing && (profile == null || !settings.removable(profile.route))) return
        retireDiscovery()
        retireCatalog()
        update(
            state.copy(
                editor =
                    ProviderEditorState(
                        settings.revision,
                        profile,
                        profile?.route.orEmpty(),
                        profile?.displayName.orEmpty(),
                        profile?.api ?: ProviderApi.OpenAiCompletions,
                        profile?.baseUrl.orEmpty(),
                        profile?.credentialRef.orEmpty(),
                        profile?.models?.joinToString("\n") { it.id }.orEmpty(),
                        removing,
                    ),
                notice = null,
            ),
        )
        if (profile != null && !profile.handDeclared && !removing) loadCatalog()
    }

    private fun edit(field: ProviderEditorField, value: String) {
        val editor = state.editor?.takeIf { !it.busy && !it.removing } ?: return
        val next =
            when (field) {
                ProviderEditorField.Route -> {
                    if (editor.original != null) return
                    val ref =
                        if (
                            editor.route.isEmpty() && editor.credentialRef.isEmpty() ||
                                editor.credentialRef == deriveProviderKeyRef(editor.route)
                        )
                            deriveProviderKeyRef(value)
                        else editor.credentialRef
                    editor.copy(route = value, credentialRef = ref)
                }
                ProviderEditorField.Name -> editor.copy(name = value)
                ProviderEditorField.BaseUrl -> editor.copy(baseUrl = value, endpointSaved = false)
                ProviderEditorField.CredentialRef -> editor.copy(credentialRef = value)
            }
        if (
            field in
                setOf(
                    ProviderEditorField.Route,
                    ProviderEditorField.BaseUrl,
                    ProviderEditorField.CredentialRef,
                )
        ) {
            retireDiscovery()
            update(state.copy(editor = next.copy(error = null, discovery = null)))
        } else update(state.copy(editor = next.copy(error = null)))
    }

    private fun protocol(api: ProviderApi) {
        state.editor
            ?.takeIf { !it.busy && !it.removing && it.full }
            ?.let {
                retireDiscovery()
                update(state.copy(editor = it.copy(api = api, error = null, discovery = null)))
            }
    }

    private fun dismissProfile(reload: Boolean = false) {
        if (state.writing) return
        retireDiscovery()
        retireCatalog()
        update(state.copy(editor = null))
        if (reload) refresh()
    }

    private fun saveProfile() {
        val editor = state.editor?.takeIf { !it.busy } ?: return
        val client = owner ?: return
        val settings = state.settings?.takeIf { it.writable } ?: return
        if (
            state.writing ||
                editor.discovery != null ||
                editor.modelEditor != null ||
                editor.contextEditor != null ||
                !editor.removing && (!editor.valid || !editor.changed)
        )
            return
        val own = connection
        if (!current(client, own)) return
        val previous = editor.original
        if (editor.removing && (previous == null || !settings.removable(previous.route))) return
        readNumber++
        read?.cancel()
        retireCatalog()
        update(
            state.copy(
                editor = editor.copy(busy = true, error = null),
                writing = true,
                loading = false,
                notice = null,
            ),
        )
        write =
            scope
                .launch {
                    var acknowledged = false
                    try {
                        val result =
                            saveProfile(
                                client,
                                settings,
                                ProviderProfileDraft(
                                    editor.revision,
                                    previous,
                                    editor.route,
                                    editor.name,
                                    editor.api,
                                    editor.baseUrl,
                                    editor.credentialRef,
                                    editor.resolvedModels(),
                                    editor.resolvedOverrides(),
                                    editor.removing,
                                ),
                            )
                        acknowledged = true
                        if (!current(client, own)) return@launch
                        update(
                            state.copy(
                                settings = result,
                                accounts = result.accounts(),
                                editor = null,
                            ),
                        )
                        refreshAfterWrite = false
                        load(client, own)
                        if (current(client, own))
                            update(state.copy(notice = "providers/provider_profile_updated"))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (current(client, own)) {
                            if (acknowledged)
                                update(
                                    state.copy(
                                        editor = null,
                                        error = "providers/provider_profile_status_unconfirmed",
                                    ),
                                )
                            else {
                                val conflict =
                                    error is ProviderOperationException &&
                                        error.code == "settings/conflict"
                                update(
                                    state.copy(
                                        editor =
                                            editor.copy(
                                                busy = false,
                                                catalogLoading = false,
                                                conflict = conflict,
                                                error =
                                                    if (conflict)
                                                        "providers/provider_profile_conflict"
                                                    else "providers/provider_profile_write_failed",
                                            ),
                                    ),
                                )
                            }
                        }
                    }
                }
                .also { job ->
                    job.invokeOnCompletion {
                        scope.launch {
                            if (current(client, own)) {
                                update(state.copy(writing = false))
                                if (observing && refreshAfterWrite) {
                                    refreshAfterWrite = false
                                    refresh()
                                }
                            }
                        }
                    }
                }
    }

    private fun openDiscovery() {
        val editor = state.editor?.takeIf { !it.busy && !it.removing && it.full } ?: return
        if (state.writing || !state.connected || !validProviderBaseUrl(editor.baseUrl)) return
        val client = owner ?: return
        if (!current(client, connection)) return
        retireDiscovery()
        val url = normalizeProviderBaseUrl(editor.baseUrl, editor.api)
        val live = editor.original?.route?.let { state.settings?.find(it) }
        val stored =
            live
                ?.takeIf {
                    it.api == editor.api &&
                        it.baseUrl?.let { base ->
                            normalizeProviderBaseUrl(base, editor.api) == url
                        } == true &&
                        it.credentialRef.orEmpty() == editor.credentialRef.trim()
                }
                ?.route
        update(
            state.copy(
                editor =
                    editor.copy(
                        discovery =
                            ProviderDiscoveryState(url, stored, awaitingInput = stored == null),
                    ),
            ),
        )
        if (stored != null) discover(null)
    }

    private fun discover(key: ProviderKey?) {
        val editor = state.editor
        val request = editor?.discovery
        val client = owner
        if (
            editor == null ||
                request == null ||
                request.loading ||
                client == null ||
                state.writing ||
                !state.connected
        ) {
            key?.close()
            return
        }
        if (request.storedRoute != null && key != null) {
            key.close()
            return
        }
        val storedRoute = request.storedRoute
        val own = connection
        if (!current(client, own)) {
            key?.close()
            return
        }
        retireDiscovery()
        val number = discoveryNumber
        updateDiscovery { it.copy(awaitingInput = false, loading = true, error = null) }
        discovery =
            scope
                .launch {
                    try {
                        val rows =
                            if (storedRoute != null) client.discover(storedRoute)
                            else
                                client.discover(
                                    baseUrl = request.baseUrl,
                                    api = editor.api,
                                    key = key,
                                )
                        if (current(client, own) && discoveryNumber == number)
                            updateDiscovery { it.copy(loading = false, models = rows) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (current(client, own) && discoveryNumber == number)
                            updateDiscovery {
                                it.copy(
                                    loading = false,
                                    awaitingInput = it.storedRoute == null,
                                    error = "providers/provider_discovery_failed",
                                )
                            }
                    }
                }
                .also { job -> job.invokeOnCompletion { key?.close() } }
    }

    private fun closeDiscovery() {
        retireDiscovery()
        state.editor?.let { update(state.copy(editor = ProviderDiscovery.close(it))) }
    }

    private fun filterDiscovery(query: String) = updateDiscovery {
        ProviderDiscovery.filtered(it, query)
    }

    private fun toggleDiscovered(id: String) {
        state.editor?.discovery?.takeIf { !it.loading } ?: return
        updateDiscovery { ProviderDiscovery.toggled(it, id) }
    }

    private fun selectDiscovered() {
        val next = state.editor?.let(ProviderDiscovery::selected) ?: return
        update(state.copy(editor = next))
    }

    private fun importDiscovered() {
        val next = state.editor?.let(ProviderDiscovery::imported) ?: return
        retireDiscovery()
        update(state.copy(editor = next))
    }

    private fun openModelParameters(id: String) {
        val next = ProviderModelParameters.open(state.editor, id, state.writing) ?: return
        update(state.copy(editor = next))
    }

    private fun loadCatalog() {
        val editor = state.editor?.takeIf { !it.full && !it.busy && !it.removing } ?: return
        val client = owner ?: return
        val own = connection
        if (!current(client, own)) return
        retireCatalog()
        val number = catalogNumber
        update(state.copy(editor = editor.copy(catalogLoading = true, catalogError = null)))
        catalogRead = scope.launch {
            try {
                val catalog = client.catalog()
                if (!current(client, own) || number != catalogNumber) return@launch
                val live = state.editor ?: return@launch
                val failed = catalog.failures.any { it.provider == live.route }
                val models =
                    catalog.groups.firstOrNull { it.id == live.route }?.models ?: persistentListOf()
                update(
                    state.copy(
                        editor =
                            live.copy(
                                catalogModels = models,
                                catalogLoading = false,
                                catalogError =
                                    if (failed) "providers/provider_catalog_failed" else null,
                            ),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (current(client, own) && number == catalogNumber)
                    state.editor?.let {
                        update(
                            state.copy(
                                editor =
                                    it.copy(
                                        catalogLoading = false,
                                        catalogError = "providers/provider_catalog_failed",
                                    ),
                            ),
                        )
                    }
            }
        }
    }

    private fun retireCatalog() {
        catalogNumber++
        catalogRead?.cancel()
        catalogRead = null
    }

    private fun openCatalogContext(id: String, name: String) {
        val next = ProviderCatalogContext.open(state.editor, id, name, state.writing) ?: return
        update(state.copy(editor = next))
    }

    private fun editCatalogContext(value: String) {
        val next = ProviderCatalogContext.edit(state.editor, value) ?: return
        update(state.copy(editor = next))
    }

    private fun applyCatalogContext() {
        val next = ProviderCatalogContext.apply(state.editor) ?: return
        update(state.copy(editor = next))
    }

    private fun closeCatalogContext() {
        val next = ProviderCatalogContext.close(state.editor) ?: return
        update(state.copy(editor = next))
    }

    private fun modelParameter(field: ModelParameterField, value: String) = updateModelParameters {
        ProviderModelParameters.field(it, field, value)
    }

    private fun modelImages(enabled: Boolean) = updateModelParameters {
        ProviderModelParameters.images(it, enabled)
    }

    private fun modelThinking(enabled: Boolean) = updateModelParameters {
        ProviderModelParameters.thinking(it, enabled)
    }

    private fun modelEffort(effort: String) = updateModelParameters {
        ProviderModelParameters.effort(it, effort, providerThinkingLevels)
    }

    private fun applyModelParameters() =
        state.editor
            ?.takeIf { !it.busy }
            ?.let { update(state.copy(editor = ProviderModelParameters.apply(it))) }

    private fun closeModelParameters() =
        state.editor?.let { update(state.copy(editor = ProviderModelParameters.close(it))) }

    private fun updateModelParameters(
        transform: (ModelParameterEditorState) -> ModelParameterEditorState,
    ) {
        val next = ProviderModelParameters.update(state.editor, transform) ?: return
        update(state.copy(editor = next))
    }

    private fun updateDiscovery(transform: (ProviderDiscoveryState) -> ProviderDiscoveryState) {
        val editor = state.editor ?: return
        val result = editor.discovery ?: return
        update(state.copy(editor = editor.copy(discovery = transform(result))))
    }

    private fun retireDiscovery() {
        discoveryNumber++
        discovery?.cancel()
        discovery = null
    }

    private fun current(client: ProvidersRepository, own: Long) =
        owner === client && connection == own && repository.value === client

    private fun update(value: ProvidersUiState) {
        mutable.value = value
    }
}

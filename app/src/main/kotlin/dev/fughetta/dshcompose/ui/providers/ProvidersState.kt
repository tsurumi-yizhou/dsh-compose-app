package dev.fughetta.dshcompose.ui.providers

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*

data class CredentialDialogState(
    val ref: String,
    val name: String,
    val removing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

enum class ProviderEditorField {
    Route,
    Name,
    BaseUrl,
    CredentialRef,
}

enum class ModelParameterField {
    Name,
    ContextWindow,
    MaxTokens,
}

data class CatalogContextEditorState(val id: String, val name: String, val context: String) {
    val valid
        get() = context.isBlank() || parseProviderTokenCount(context) != null
}

data class ModelParameterEditorState(
    val source: ProviderModelSpec,
    val name: String,
    val context: String,
    val output: String,
    val images: Boolean,
    val initialImages: Boolean,
    val thinking: Boolean,
    val levels: PersistentSet<String>,
    val offersOff: Boolean,
    val defaultContext: Long? = null,
    val defaultOutput: Long? = null,
) {
    val valid
        get() =
            (context.isBlank() || parseProviderTokenCount(context) != null) &&
                (output.isBlank() || parseProviderTokenCount(output) != null) &&
                (!thinking || levels.isNotEmpty())

    fun result(): ProviderModelSpec {
        val desired =
            if (thinking) levels.addingAll(if (offersOff) setOf("off") else emptySet())
            else persistentSetOf()
        return source.withParameters(
            name,
            parseProviderTokenCount(context),
            parseProviderTokenCount(output),
            images = images.takeIf { it != initialImages },
            efforts =
                desired.takeIf {
                    thinking != source.thinks || thinking && desired != source.reasoningLevels
                },
        )
    }
}

data class ProviderDiscoveryState(
    val baseUrl: String,
    val storedRoute: String?,
    val awaitingInput: Boolean = false,
    val loading: Boolean = false,
    val models: PersistentList<DiscoveredModel> = persistentListOf(),
    val selected: PersistentSet<String> = persistentSetOf(),
    val query: String = "",
    val error: String? = null,
)

data class ProviderEditorState(
    val revision: Long,
    val original: ProviderProfile? = null,
    val route: String = "",
    val name: String = "",
    val api: ProviderApi = ProviderApi.OpenAiCompletions,
    val baseUrl: String = "",
    val credentialRef: String = "",
    val modelIds: String = "",
    val removing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val conflict: Boolean = false,
    val discovery: ProviderDiscoveryState? = null,
    val importedModels: PersistentMap<String, ProviderModelSpec> = persistentMapOf(),
    val editedModels: PersistentMap<String, ProviderModelSpec> = persistentMapOf(),
    val modelEditor: ModelParameterEditorState? = null,
    val catalogModels: PersistentList<ModelInfo> = persistentListOf(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val editedOverrides: PersistentMap<String, ProviderModelOverride> = persistentMapOf(),
    val contextEditor: CatalogContextEditorState? = null,
    val endpointOnly: Boolean = false,
    val endpointSaved: Boolean = false,
    val endpointNeedsKey: Boolean = false,
) {
    val endpointUrl: String
        get() {
            val raw = baseUrl.trim()
            if (raw.isEmpty()) return raw
            val address =
                if ("://" in raw) raw else (if (route == "magpie") "http://" else "https://") + raw
            return normalizeProviderBaseUrl(address, api)
        }

    val full
        get() = original?.handDeclared ?: true

    val models
        get() =
            modelIds.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    val valid
        get() =
            validProviderRoute(route) &&
                (credentialRef.isBlank() || validCredentialRef(credentialRef.trim())) &&
                (!full || validProviderBaseUrl(baseUrl) && models.isNotEmpty())

    val changed
        get() =
            original == null ||
                name != original.displayName.orEmpty() &&
                    name.trim() != original.displayName.orEmpty() ||
                credentialRef.trim() != original.credentialRef.orEmpty() ||
                full &&
                    (api != original.api ||
                        baseUrl != original.baseUrl &&
                            normalizeProviderBaseUrl(baseUrl, api) != original.baseUrl ||
                        models != original.models.map { it.id }) ||
                models.any { it in editedModels } ||
                editedOverrides.isNotEmpty()

    fun modelOverride(id: String): ProviderModelOverride =
        editedOverrides[id] ?: original?.modelOverrides?.get(id) ?: ProviderModelOverride.empty()

    fun resolvedOverrides(): Map<String, ProviderModelOverride> =
        original?.modelOverrides.orEmpty().plus(editedOverrides).filterValues { !it.isEmpty }

    fun applyCatalogContext(): ProviderEditorState {
        val editor = contextEditor?.takeIf { it.valid } ?: return this
        val next =
            modelOverride(editor.id).withContextWindow(parseProviderTokenCount(editor.context))
        val originalValue =
            original?.modelOverrides?.get(editor.id) ?: ProviderModelOverride.empty()
        return copy(
            contextEditor = null,
            editedOverrides =
                if (next.sameSettings(originalValue)) editedOverrides.removing(editor.id)
                else editedOverrides.putting(editor.id, next),
            error = null,
        )
    }

    fun resolvedModels(): List<ProviderModelSpec> {
        val existing = original?.models?.associateBy { it.id }.orEmpty()
        return models.map { id ->
            editedModels[id] ?: existing[id] ?: importedModels[id] ?: ProviderModelSpec.create(id)
        }
    }

    fun applyModelParameters(): ProviderEditorState {
        val editor = modelEditor?.takeIf { it.valid } ?: return this
        val result = editor.result()
        val baseline =
            original?.models?.firstOrNull { it.id == result.id }
                ?: importedModels[result.id]
                ?: ProviderModelSpec.create(result.id)
        return copy(
            modelEditor = null,
            editedModels =
                if (result.sameSettings(baseline)) editedModels.removing(result.id)
                else editedModels.putting(result.id, result),
            error = null,
        )
    }

    fun withDiscoveredModels(): ProviderEditorState {
        val result =
            discovery?.takeIf { !it.loading && !it.awaitingInput && it.selected.isNotEmpty() }
                ?: return this
        val ids = models.toMutableSet()
        val selected = result.models.filter { it.id in result.selected && ids.add(it.id) }
        val existing = original?.models?.mapTo(hashSetOf()) { it.id }.orEmpty()
        val imported =
            importedModels.puttingAll(
                selected.filter { it.id !in existing }.associate { it.id to it.toSpec() },
            )
        return copy(
            modelIds = ids.joinToString("\n"),
            importedModels = imported,
            discovery = null,
            error = null,
        )
    }
}

data class ProvidersUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val accounts: PersistentList<ProviderAccount> = persistentListOf(),
    val credentials: PersistentMap<String, CredentialStatus> = persistentMapOf(),
    val settings: ProviderSettings? = null,
    val editor: ProviderEditorState? = null,
    val writing: Boolean = false,
    val dialog: CredentialDialogState? = null,
    val error: String? = null,
    val notice: String? = null,
)

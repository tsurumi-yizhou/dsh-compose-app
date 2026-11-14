package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.providers.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderEditorDialog(state: ProviderEditorState, onAction: (AppAction) -> Unit) {
    val input = rememberCredentialInput(state.route, state.endpointSaved)
    val canFetch =
        validProviderRoute(state.route) &&
            validProviderBaseUrl(state.endpointUrl) &&
            (state.credentialRef.isBlank() || validCredentialRef(state.credentialRef.trim()))
    val models =
        remember(state.original, state.modelIds, state.importedModels, state.editedModels) {
            state.resolvedModels()
        }
    var query by remember(state.route) { mutableStateOf("") }
    val shownModels =
        remember(models, query) {
            models.filter {
                it.id.contains(query.trim(), true) || it.name?.contains(query.trim(), true) == true
            }
        }
    val catalogModels =
        remember(state.catalogModels, state.original) {
            val names = state.catalogModels.associate { it.id to it.name }.toMutableMap()
            state.original?.modelOverrides?.keys?.forEach { names.putIfAbsent(it, it) }
            names.toList()
        }
    val shownCatalogModels =
        remember(catalogModels, query) {
            catalogModels.filter { (id, name) ->
                id.contains(query.trim(), true) || name.contains(query.trim(), true)
            }
        }
    val title =
        when {
            state.removing -> R.string.remove_provider
            state.original == null -> R.string.add_provider
            else -> R.string.edit_provider
        }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!state.busy) onAction(AppAction.CloseProviderEditor) },
        title = { Text(stringResource(title)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.removing)
                    item {
                        Text(
                            stringResource(
                                R.string.remove_provider_details,
                                state.name.ifBlank { state.route },
                            ),
                        )
                    }
                else {
                    item {
                        OutlinedTextField(
                            value = state.route,
                            onValueChange = {
                                onAction(AppAction.EditProviderField(ProviderEditorField.Route, it))
                            },
                            enabled = !state.busy && !state.endpointSaved && state.original == null,
                            singleLine = true,
                            label = { Text(stringResource(R.string.provider_route)) },
                            supportingText = { Text(stringResource(R.string.provider_route_help)) },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = {
                                onAction(AppAction.EditProviderField(ProviderEditorField.Name, it))
                            },
                            enabled = !state.busy && !state.endpointSaved,
                            singleLine = true,
                            label = { Text(stringResource(R.string.provider_display_name)) },
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.credentialRef,
                            onValueChange = {
                                onAction(
                                    AppAction.EditProviderField(
                                        ProviderEditorField.CredentialRef,
                                        it,
                                    ),
                                )
                            },
                            enabled = !state.busy && !state.endpointSaved,
                            singleLine = true,
                            label = { Text(stringResource(R.string.provider_key_reference)) },
                            supportingText = {
                                Text(stringResource(R.string.provider_key_reference_help))
                            },
                        )
                    }
                    if (state.full) {
                        item {
                            Column(Modifier.selectableGroup()) {
                                Text(
                                    stringResource(R.string.provider_protocol),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                for (api in ProviderApi.entries) Row(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .selectable(
                                                selected = state.api == api,
                                                enabled = !state.busy && !state.endpointSaved,
                                                role = Role.RadioButton,
                                                onClick = {
                                                    onAction(AppAction.ChangeProviderApi(api))
                                                },
                                            ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                                ) {
                                    Text(
                                        stringResource(
                                            when (api) {
                                                ProviderApi.OpenAiCompletions ->
                                                    R.string.provider_api_completions
                                                ProviderApi.OpenAiResponses ->
                                                    R.string.provider_api_responses
                                                ProviderApi.AnthropicMessages ->
                                                    R.string.provider_api_anthropic
                                            },
                                        ),
                                        Modifier.weight(1f),
                                    )
                                    RadioButton(
                                        selected = state.api == api,
                                        onClick = null,
                                        enabled = !state.busy && !state.endpointSaved,
                                    )
                                }
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = state.baseUrl,
                                onValueChange = {
                                    onAction(
                                        AppAction.EditProviderField(
                                            ProviderEditorField.BaseUrl,
                                            it,
                                        ),
                                    )
                                },
                                enabled = !state.busy && !state.endpointSaved,
                                singleLine = true,
                                label = { Text(stringResource(R.string.provider_base_url)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (state.route == "magpie")
                                                R.string.magpie_endpoint_help
                                            else R.string.provider_base_url_help,
                                        ),
                                    )
                                },
                            )
                        }
                        item {
                            OutlinedSecureTextField(
                                state = input,
                                enabled = !state.busy && !state.endpointSaved,
                                textObfuscationMode = TextObfuscationMode.Hidden,
                                label = { Text("API Key") },
                                supportingText = {
                                    Text(stringResource(R.string.provider_auto_models_help))
                                },
                            )
                        }
                        if (models.isNotEmpty())
                            item {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.filter_models)) },
                                )
                            }
                        items(
                            shownModels,
                            key = { "parameters:${it.id}" },
                            contentType = { "model-parameters" },
                        ) { model ->
                            TextButton(
                                onClick = { onAction(AppAction.OpenModelParameters(model.id)) },
                                enabled = !state.busy && !state.endpointSaved,
                            ) {
                                Column {
                                    Text(model.name ?: model.id)
                                    if (model.name != null)
                                        Text(model.id, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        item { Text(stringResource(R.string.provider_catalog_profile)) }
                        if (state.catalogLoading)
                            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        state.catalogError?.let { error ->
                            item { Text(error, color = MaterialTheme.colorScheme.error) }
                        }
                        item {
                            TextButton(
                                onClick = { onAction(AppAction.ReloadProviderCatalog) },
                                enabled = !state.busy && !state.catalogLoading,
                            ) {
                                Text(stringResource(CoreDesignsystemR.string.refresh))
                            }
                        }
                        if (catalogModels.isNotEmpty())
                            item {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.filter_models)) },
                                )
                            }
                        items(
                            shownCatalogModels,
                            key = { "context:${it.first}" },
                            contentType = { "catalog-context" },
                        ) { (id, name) ->
                            TextButton(
                                onClick = { onAction(AppAction.OpenCatalogContext(id, name)) },
                                enabled = !state.busy && !state.endpointSaved,
                            ) {
                                Column {
                                    Text(name)
                                    if (name != id)
                                        Text(id, style = MaterialTheme.typography.bodySmall)
                                    state.modelOverride(id).contextWindow?.let {
                                        Text(
                                            stringResource(
                                                R.string.model_context_override_value,
                                                it,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { error ->
                    item {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        if (state.conflict)
                            TextButton(
                                onClick = { onAction(AppAction.DiscardProviderEditsAndRefresh) },
                            ) {
                                Text(stringResource(R.string.discard_provider_edits))
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        state.endpointSaved -> onAction(AppAction.CloseProviderEditor)
                        state.full && !state.removing ->
                            onAction(AppAction.SaveProviderEndpoint(input.takeCredentialInput()))
                        else -> onAction(AppAction.SaveProviderProfile)
                    }
                },
                enabled =
                    !state.busy &&
                        (state.endpointSaved ||
                            state.removing ||
                            if (state.full) canFetch else state.valid && state.changed),
            ) {
                Text(
                    stringResource(
                        when {
                            state.endpointSaved -> CoreDesignsystemR.string.close
                            state.removing -> CoreDesignsystemR.string.remove
                            state.full -> R.string.provider_fetch_save
                            else -> CoreDesignsystemR.string.confirm
                        },
                    ),
                    color =
                        if (state.removing) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(AppAction.CloseProviderEditor) },
                enabled = !state.busy && !state.endpointSaved,
            ) {
                Text(stringResource(CoreDesignsystemR.string.cancel))
            }
        },
    )
    if (state.discovery != null) ProviderDiscoveryDialog(state, onAction)
    state.modelEditor?.let { ModelParametersDialog(it, onAction) }
    state.contextEditor?.let { editor ->
        AlertDialog(
            onDismissRequest = { onAction(AppAction.CloseCatalogContext) },
            title = { Text(editor.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TokenCountField(editor.context, null, R.string.model_context_window) {
                        onAction(AppAction.EditCatalogContext(it))
                    }
                    Text(stringResource(R.string.model_catalog_context_help))
                    Text(
                        stringResource(R.string.model_parameters_draft_notice),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(AppAction.ApplyCatalogContext) },
                    enabled = editor.valid,
                ) {
                    Text(stringResource(CoreDesignsystemR.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(AppAction.CloseCatalogContext) }) {
                    Text(stringResource(CoreDesignsystemR.string.cancel))
                }
            },
        )
    }
}

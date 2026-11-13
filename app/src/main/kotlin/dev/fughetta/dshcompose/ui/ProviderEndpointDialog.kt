package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.providers.validProviderBaseUrl
import dev.fughetta.dshcompose.ui.providers.ProviderEditorField
import dev.fughetta.dshcompose.ui.providers.ProviderEditorState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderEndpointDialog(state: ProviderEditorState, onAction: (AppAction) -> Unit) {
    val input = rememberCredentialInput(state.route, state.endpointSaved)
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!state.busy) onAction(AppAction.CloseProviderEditor) },
        title = { Text(state.name) },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.endpointSaved) {
                    item {
                        OutlinedTextField(
                            value = state.baseUrl,
                            onValueChange = {
                                onAction(
                                    AppAction.EditProviderField(ProviderEditorField.BaseUrl, it),
                                )
                            },
                            enabled = !state.busy,
                            singleLine = true,
                            label = { Text(stringResource(R.string.provider_base_url)) },
                        )
                    }
                    item {
                        OutlinedSecureTextField(
                            state = input,
                            enabled = !state.busy,
                            textObfuscationMode = TextObfuscationMode.Hidden,
                            label = { Text("API Key") },
                            supportingText =
                                if (state.route == "magpie") {
                                    { Text(stringResource(R.string.endpoint_magpie_default_key)) }
                                } else if (
                                    !state.endpointNeedsKey &&
                                        state.original?.baseUrl == state.endpointUrl
                                ) {
                                    { Text(stringResource(R.string.endpoint_keep_key)) }
                                } else null,
                        )
                    }
                }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                if (state.models.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.endpoint_model_count, state.models.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    items(state.models, key = { it }) { id ->
                        val model = state.importedModels[id]
                        ListItem(
                            headlineContent = { Text(model?.name ?: id) },
                            supportingContent =
                                if (model?.name != null && model.name != id) {
                                    { Text(id) }
                                } else null,
                            colors =
                                ListItemDefaults.colors(
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    !state.busy && (state.endpointSaved || validProviderBaseUrl(state.endpointUrl)),
                onClick = {
                    if (state.endpointSaved) onAction(AppAction.CloseProviderEditor)
                    else onAction(AppAction.SaveProviderEndpoint(input.takeCredentialInput()))
                },
            ) {
                Text(stringResource(if (state.endpointSaved) R.string.close else R.string.confirm))
            }
        },
        dismissButton = {
            if (!state.endpointSaved)
                TextButton(
                    enabled = !state.busy,
                    onClick = { onAction(AppAction.CloseProviderEditor) },
                ) {
                    Text(stringResource(R.string.cancel))
                }
        },
    )
}

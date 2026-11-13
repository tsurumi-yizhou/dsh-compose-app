package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.providers.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderDiscoveryDialog(editor: ProviderEditorState, onAction: (AppAction) -> Unit) {
    val state = editor.discovery ?: return
    val input = rememberCredentialInput(state.baseUrl, state.storedRoute)
    val added = remember(editor.modelIds) { editor.models.toHashSet() }
    val models =
        remember(state.models, state.query) {
            val query = state.query.trim()
            if (query.isEmpty()) state.models
            else
                state.models.filter {
                    it.id.contains(query, true) || it.name?.contains(query, true) == true
                }
        }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { onAction(AppAction.CloseProviderDiscovery) },
        title = { Text(stringResource(R.string.discover_models)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(
                            if (state.storedRoute != null) R.string.discovery_saved_credentials
                            else R.string.discovery_temporary_credentials,
                        ),
                    )
                    if (state.storedRoute == null)
                        Text(state.baseUrl, style = MaterialTheme.typography.bodySmall)
                }
                if (state.awaitingInput)
                    item {
                        OutlinedSecureTextField(
                            state = input,
                            textObfuscationMode = TextObfuscationMode.Hidden,
                            label = { Text(stringResource(R.string.discovery_optional_key)) },
                        )
                    }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { error ->
                    item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
                if (!state.awaitingInput && !state.loading) {
                    if (state.models.isEmpty())
                        item {
                            if (state.error == null) Text(stringResource(R.string.discovery_empty))
                            TextButton(onClick = { onAction(AppAction.OpenProviderDiscovery) }) {
                                Text(stringResource(R.string.refresh))
                            }
                        }
                    else {
                        item {
                            OutlinedTextField(
                                value = state.query,
                                onValueChange = { onAction(AppAction.FilterDiscoveredModels(it)) },
                                singleLine = true,
                                label = { Text(stringResource(R.string.filter_models)) },
                            )
                        }
                        item {
                            Text(
                                stringResource(
                                    R.string.discovery_selected_count,
                                    state.selected.size,
                                ),
                            )
                            TextButton(
                                onClick = { onAction(AppAction.SelectAllDiscoveredModels) },
                            ) {
                                Text(
                                    stringResource(
                                        if (state.query.isBlank()) R.string.select_all_models
                                        else R.string.select_filtered_models,
                                    ),
                                )
                            }
                        }
                        items(models, key = { it.id }, contentType = { "discovered-model" }) { model
                            ->
                            val exists = model.id in added
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = exists || model.id in state.selected,
                                        role = Role.Checkbox,
                                        enabled = !exists,
                                        onValueChange = {
                                            onAction(AppAction.ToggleDiscoveredModel(model.id))
                                        },
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = exists || model.id in state.selected,
                                    onCheckedChange = null,
                                    enabled = !exists,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        model.name ?: model.id,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (model.name != null && model.name != model.id)
                                        Text(model.id, style = MaterialTheme.typography.bodySmall)
                                    model.contextWindow?.let {
                                        Text(
                                            stringResource(R.string.model_context_size, it),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    model.maxTokens?.let {
                                        Text(
                                            stringResource(R.string.model_output_size, it),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (exists)
                                        Text(
                                            stringResource(R.string.model_already_added),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.awaitingInput)
                TextButton(
                    onClick = {
                        onAction(AppAction.SubmitProviderDiscovery(input.takeCredentialInput()))
                    },
                ) {
                    Text(stringResource(R.string.read_models))
                }
            else
                TextButton(
                    onClick = { onAction(AppAction.ImportDiscoveredModels) },
                    enabled = !state.loading && state.selected.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.import_selected_models))
                }
        },
        dismissButton = {
            TextButton(onClick = { onAction(AppAction.CloseProviderDiscovery) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

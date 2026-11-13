package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.providers.*

@Composable
internal fun ModelParametersDialog(
    state: ModelParameterEditorState,
    onAction: (AppAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseModelParameters) },
        title = { Text(stringResource(R.string.model_parameters)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(state.source.id, style = MaterialTheme.typography.titleSmall) }
                item {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = {
                            onAction(AppAction.EditModelParameter(ModelParameterField.Name, it))
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.model_display_name)) },
                    )
                }
                item {
                    TokenCountField(
                        state.context,
                        state.defaultContext,
                        R.string.model_context_window,
                    ) {
                        onAction(
                            AppAction.EditModelParameter(ModelParameterField.ContextWindow, it),
                        )
                    }
                }
                item {
                    TokenCountField(state.output, state.defaultOutput, R.string.model_max_output) {
                        onAction(AppAction.EditModelParameter(ModelParameterField.MaxTokens, it))
                    }
                }
                item {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .toggleable(
                                    value = state.images,
                                    role = Role.Switch,
                                    onValueChange = { onAction(AppAction.ChangeModelImages(it)) },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                    ) {
                        Text(stringResource(R.string.model_accepts_images), Modifier.weight(1f))
                        Switch(checked = state.images, onCheckedChange = null)
                    }
                }
                item {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .toggleable(
                                    value = state.thinking,
                                    role = Role.Switch,
                                    onValueChange = { onAction(AppAction.ChangeModelThinking(it)) },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                    ) {
                        Text(stringResource(R.string.model_offers_thinking), Modifier.weight(1f))
                        Switch(checked = state.thinking, onCheckedChange = null)
                    }
                }
                if (state.thinking) {
                    item { Text(stringResource(R.string.model_thinking_help)) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(providerThinkingLevels, key = { it }) { level ->
                                FilterChip(
                                    selected = level in state.levels,
                                    onClick = { onAction(AppAction.ToggleModelEffort(level)) },
                                    label = { Text(effortName(level)) },
                                )
                            }
                        }
                    }
                    if (state.levels.isEmpty())
                        item {
                            Text(
                                stringResource(R.string.model_need_effort),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                }
                item {
                    Text(
                        stringResource(R.string.model_parameters_draft_notice),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(AppAction.ApplyModelParameters) },
                enabled = state.valid,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(AppAction.CloseModelParameters) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun TokenCountField(
    value: String,
    fallback: Long?,
    label: Int,
    onChange: (String) -> Unit,
) {
    val invalid = value.isNotBlank() && parseProviderTokenCount(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        isError = invalid,
        label = { Text(stringResource(label)) },
        supportingText = {
            Text(
                when {
                    invalid -> stringResource(R.string.model_token_count_invalid)
                    fallback != null -> stringResource(R.string.model_token_count_default, fallback)
                    else -> stringResource(R.string.model_token_count_help)
                },
            )
        },
    )
}

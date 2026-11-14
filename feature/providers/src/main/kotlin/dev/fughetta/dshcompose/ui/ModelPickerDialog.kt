package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.models.ModelPickerState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ModelPickerRoute(
    source: StateFlow<ModelPickerState?>,
    onAction: (AppAction) -> Unit,
    onProviders: () -> Unit,
) {
    val state by source.collectAsStateWithLifecycle()
    state?.let { dialog ->
        val error =
            when (dialog.error) {
                "models/offline" -> stringResource(CoreDesignsystemR.string.engine_not_connected)
                "models/load-failed" -> stringResource(R.string.models_load_failed)
                "models/select-failed" -> stringResource(R.string.model_change_failed)
                else -> dialog.error
            }
        ModelPickerDialog(dialog.copy(error = error), onAction, onProviders)
    }
}

@Composable
internal fun ModelPickerDialog(
    state: ModelPickerState,
    onAction: (AppAction) -> Unit,
    onProviders: () -> Unit,
) {
    ModelChoicesDialog(
        state.catalog,
        state.candidate,
        state.loading,
        state.busy,
        state.error,
        hint = R.string.model_applies_next,
        onChoose = { onAction(AppAction.ChooseModel(it)) },
        onConfirm = { onAction(AppAction.ConfirmModel) },
        onDismiss = { onAction(AppAction.CloseModelPicker) },
        onReload = { onAction(AppAction.ReloadModels) },
        onProviders = onProviders,
    )
}

@Composable
internal fun ModelChoicesDialog(
    catalog: ModelCatalog?,
    candidate: ModelSelection?,
    loading: Boolean,
    busy: Boolean,
    error: String?,
    hint: Int,
    onChoose: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onProviders: (() -> Unit)?,
) {
    val valid = candidate != null && catalog?.accepts(candidate) == true
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(CoreDesignsystemR.string.select_model)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 480.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Text(stringResource(hint)) }
                if (loading || busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload, enabled = !busy) {
                            Text(stringResource(CoreDesignsystemR.string.refresh))
                        }
                    }
                }
                if (catalog != null) {
                    if (catalog.groups.all { it.models.isEmpty() })
                        item {
                            Text(stringResource(R.string.no_models))
                            if (onProviders != null)
                                TextButton(onClick = onProviders, enabled = !busy) {
                                    Text(stringResource(CoreDesignsystemR.string.providers))
                                }
                        }
                    items(catalog.failures, key = { "failure:${it.provider}" }) { failure ->
                        Text(
                            stringResource(R.string.provider_models_failed, failure.name),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    for (group in catalog.groups) {
                        item(key = "provider:${group.id}") {
                            Text(group.name, style = MaterialTheme.typography.titleSmall)
                        }
                        items(
                            group.models,
                            key = { "model:${it.provider}:${it.id}" },
                            contentType = { "model" },
                        ) { model ->
                            val selected =
                                candidate?.provider == model.provider && candidate.model == model.id
                            val enabled =
                                !busy && !loading && model.provider in catalog.routableProviders
                            Column {
                                ListItem(
                                    modifier =
                                        Modifier.selectable(
                                            selected = selected,
                                            enabled = enabled,
                                            role = Role.RadioButton,
                                            onClick = {
                                                onChoose(
                                                    ModelSelection(
                                                        model.provider,
                                                        model.id,
                                                        model.initialEffort,
                                                    ),
                                                )
                                            },
                                        ),
                                    leadingContent = {
                                        RadioButton(
                                            selected = selected,
                                            onClick = null,
                                            enabled = enabled,
                                        )
                                    },
                                    supportingContent = model.description?.let { { Text(it) } },
                                ) {
                                    Text(model.name)
                                }
                                if (selected && model.efforts.isNotEmpty())
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(model.efforts, key = { it.id }) { effort ->
                                            FilterChip(
                                                selected = candidate.reasoningEffort == effort.id,
                                                enabled = enabled,
                                                onClick = {
                                                    onChoose(
                                                        ModelSelection(
                                                            model.provider,
                                                            model.id,
                                                            effort.id,
                                                        ),
                                                    )
                                                },
                                                label = {
                                                    Text(effortName(effort.id, effort.name))
                                                },
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = valid && !busy && !loading) {
                Text(stringResource(CoreDesignsystemR.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(CoreDesignsystemR.string.cancel))
            }
        },
    )
}

@Composable
fun effortName(id: String, fallback: String = id): String =
    when (id) {
        "off",
        "none" -> stringResource(R.string.thinking_off)
        "minimal" -> stringResource(R.string.thinking_minimal)
        "low" -> stringResource(R.string.thinking_low)
        "medium" -> stringResource(R.string.thinking_medium)
        "high" -> stringResource(R.string.thinking_high)
        "xhigh" -> stringResource(R.string.thinking_xhigh)
        "max" -> stringResource(R.string.thinking_max)
        else -> fallback
    }

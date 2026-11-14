package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.presets.*

/** What the engine published for a preset: its name, else its id. */
fun probedPresetName(preset: AgentPreset): String =
    preset.name?.takeIf(String::isNotBlank) ?: preset.id

/** Chooses the conversation's mode from the roster the engine reports; nothing is built in. */
@Composable
fun AgentPresetPickerDialog(
    catalog: AgentPresetsUiState,
    shown: String?,
    switchable: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
) {
    var candidate by rememberSaveable { mutableStateOf(shown) }
    val valid = catalog.presets.any { it.id == candidate && it.broken == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreDesignsystemR.string.agent_presets)) },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 480.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        stringResource(
                            if (switchable) R.string.preset_applies_first
                            else R.string.preset_locked,
                        ),
                    )
                }
                if (!catalog.connected)
                    item { Text(stringResource(CoreDesignsystemR.string.engine_not_connected)) }
                if (catalog.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                catalog.error?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onReload, enabled = !catalog.loading) {
                            Text(stringResource(CoreDesignsystemR.string.refresh))
                        }
                    }
                }
                if (catalog.connected && !catalog.loading && catalog.presets.isEmpty())
                    item { Text(stringResource(R.string.preset_no_rows)) }
                items(catalog.presets, key = { it.id }) { preset ->
                    val selected = preset.id == candidate
                    val enabled = switchable && !catalog.loading && preset.broken == null
                    ListItem(
                        modifier =
                            Modifier.selectable(
                                selected = selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { candidate = preset.id },
                            ),
                        leadingContent = {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                        },
                        supportingContent =
                            (preset.broken?.let { stringResource(R.string.preset_broken) }
                                    ?: preset.description?.takeIf(String::isNotBlank))
                                ?.let { { Text(it) } },
                    ) {
                        Text(probedPresetName(preset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { candidate?.let(onConfirm) },
                enabled = valid && switchable && !catalog.loading,
            ) {
                Text(stringResource(CoreDesignsystemR.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreDesignsystemR.string.cancel))
            }
        },
    )
}

package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.files.R

@Composable
fun WorkspaceExportDialog(state: WorkspaceExportUiState, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseWorkspaceExport) },
        title = { Text(state.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.workspace_export_progress, state.bytes))
                if (state.saved) Text(stringResource(R.string.workspace_export_saved))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AppAction.CloseWorkspaceExport) }) {
                Text(
                    stringResource(
                        if (state.busy) CoreDesignsystemR.string.cancel
                        else CoreDesignsystemR.string.confirm,
                    ),
                )
            }
        },
    )
}

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
fun SessionExportDialog(state: SessionExportUiState, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseSessionExport) },
        title = { Text(stringResource(CoreDesignsystemR.string.session_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.name)
                Text(stringResource(R.string.session_export_contents))
                if (!state.supported) Text(stringResource(R.string.session_export_unsupported))
                else {
                    if (state.busy) {
                        val total = state.total
                        if (total != null && total > 0)
                            LinearProgressIndicator(
                                progress = {
                                    (state.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.session_export_progress, state.bytes))
                    }
                    if (state.needsPermission)
                        Text(stringResource(R.string.session_export_permission_wait))
                    state.savedTo?.let { Text(stringResource(R.string.session_export_saved, it)) }
                    if (state.shared) Text(stringResource(R.string.session_export_shared))
                    state.error?.let { code ->
                        Text(
                            stringResource(
                                when (code) {
                                    "permission" -> R.string.session_export_permission_denied
                                    "session-export/http-404" -> R.string.session_export_missing
                                    "session-export/unsupported" ->
                                        R.string.session_export_unsupported
                                    else -> R.string.session_export_failed
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                onAction(AppAction.ExportSession(SessionExportOperation.Share))
                            },
                            enabled = !state.busy && state.share == null,
                        ) {
                            Text(stringResource(R.string.session_export_share))
                        }
                        TextButton(
                            onClick = {
                                onAction(AppAction.ExportSession(SessionExportOperation.Save))
                            },
                            enabled = !state.busy && state.share == null,
                        ) {
                            Text(stringResource(R.string.session_export_save))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AppAction.CloseSessionExport) }) {
                Text(
                    stringResource(
                        if (state.busy) CoreDesignsystemR.string.cancel
                        else CoreDesignsystemR.string.close,
                    ),
                )
            }
        },
    )
}

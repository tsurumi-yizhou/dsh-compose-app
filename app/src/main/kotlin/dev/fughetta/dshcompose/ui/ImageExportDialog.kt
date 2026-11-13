package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R

@Composable
internal fun ImageExportDialog(state: ImageExportUiState, onAction: (AppAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseImageExport) },
        title = {
            Text(
                state.name
                    ?: stringResource(
                        if (state.operation == ImageExportOperation.Save) R.string.image_save
                        else R.string.image_share,
                    ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.needsPermission) Text(stringResource(R.string.image_storage_needed))
                if (state.saved) Text(stringResource(R.string.image_saved))
                if (state.shared) Text(stringResource(R.string.image_share_opened))
                state.error?.let { code ->
                    Text(
                        stringResource(
                            when (code) {
                                "image/permission" -> R.string.image_storage_needed
                                "image/changed" -> R.string.image_export_changed
                                "image/unsupported" -> R.string.image_export_unsupported
                                "image/too-large",
                                "workspace-file/too-large" -> R.string.image_export_too_large
                                "image/connection-changed" ->
                                    R.string.image_export_connection_changed
                                else -> R.string.image_export_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (code == "image/permission")
                        TextButton(onClick = { onAction(AppAction.OpenImageSettings) }) {
                            Text(stringResource(R.string.image_storage_settings))
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AppAction.CloseImageExport) }) {
                Text(stringResource(if (state.busy) R.string.cancel else R.string.confirm))
            }
        },
    )
}

package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R

@Composable
internal fun ReplyLinkDialog(state: ReplyLinkUiState, onAction: (AppAction) -> Unit) {
    val uri = LocalUriHandler.current
    var failed by remember(state.id) { mutableStateOf(false) }
    var opening by remember(state.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAction(AppAction.CloseReplyLink(state.id)) },
        title = { Text(stringResource(R.string.markdown_open_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(state.external ?: state.target) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (failed)
                    Text(
                        stringResource(R.string.markdown_link_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.external != null && !state.busy && !opening,
                onClick = {
                    val address = state.external ?: return@TextButton
                    opening = true
                    try {
                        uri.openUri(address)
                        onAction(AppAction.CloseReplyLink(state.id))
                    } catch (_: IllegalArgumentException) {
                        failed = true
                        opening = false
                    } catch (_: SecurityException) {
                        failed = true
                        opening = false
                    }
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(AppAction.CloseReplyLink(state.id)) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

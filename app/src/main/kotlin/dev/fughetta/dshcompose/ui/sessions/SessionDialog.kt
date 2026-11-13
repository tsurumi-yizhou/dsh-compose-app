package dev.fughetta.dshcompose.ui.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun SessionDialogRoute(
    source: StateFlow<SessionDialogState?>,
    onAction: (AppAction) -> Unit,
) {
    val state by source.collectAsStateWithLifecycle()
    state?.let { SessionDialog(it, onAction) }
}

@Composable
private fun SessionDialog(state: SessionDialogState, onAction: (AppAction) -> Unit) {
    val title =
        when (state.operation) {
            SessionOperation.Rename -> R.string.rename_session
            SessionOperation.Fork -> R.string.fork_session
            SessionOperation.Delete -> R.string.delete_session
        }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onAction(AppAction.DismissSessionDialog) },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.operation == SessionOperation.Rename)
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = { onAction(AppAction.EditSessionTitle(it)) },
                        enabled = !state.busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.session_title)) },
                    )
                else
                    Text(
                        stringResource(
                            if (state.operation == SessionOperation.Delete)
                                R.string.delete_session_details
                            else R.string.fork_session_details,
                        ),
                    )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.errorCode?.let {
                    Text(sessionOperationError(it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(AppAction.ConfirmSessionOperation) },
                enabled =
                    !state.busy &&
                        (state.operation != SessionOperation.Rename || state.title.isNotBlank()),
            ) {
                Text(
                    stringResource(
                        if (state.operation == SessionOperation.Delete) R.string.delete_permanently
                        else R.string.confirm,
                    ),
                    color =
                        if (state.operation == SessionOperation.Delete)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(AppAction.DismissSessionDialog) },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun sessionOperationError(code: String): String =
    when (code) {
        "not-connected" -> stringResource(R.string.engine_not_connected)
        "connection-lost" -> stringResource(R.string.session_outcome_unknown)
        "session-active",
        "session-running",
        "writer-held" -> stringResource(R.string.session_active)
        "session-related" -> stringResource(R.string.session_related)
        "not-found",
        "session/not-found" -> stringResource(R.string.session_not_found)
        "unavailable" -> stringResource(R.string.session_deletion_unavailable)
        else -> stringResource(R.string.session_operation_failed, code)
    }

package dev.fughetta.dshcompose.ui.commands

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.AppAction

@Composable
internal fun CommandsPalette(
    state: CommandsUiState,
    enabled: Boolean,
    onAction: (AppAction) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(8.dp)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onAction(AppAction.RefreshCommands) }, enabled = enabled) {
                    Text(stringResource(R.string.refresh))
                }
            }
            val hint = state.hint
            if (hint != null) {
                Text("/${hint.name} · ${hint.label}", style = MaterialTheme.typography.labelLarge)
                Text(hint.hint.orEmpty(), style = MaterialTheme.typography.bodySmall)
            } else
                LazyColumn {
                    if (state.commands.isEmpty() && !state.loading && state.error == null)
                        item { Text(stringResource(R.string.commands_empty)) }
                    items(state.commands, key = { it.name }, contentType = { "slash-command" }) {
                        command ->
                        TextButton(
                            onClick = {
                                onAction(AppAction.PickCommand(state.sessionId, command.name))
                            },
                            enabled = enabled,
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(
                                    "/${command.name} · ${command.label}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
        }
    }
}

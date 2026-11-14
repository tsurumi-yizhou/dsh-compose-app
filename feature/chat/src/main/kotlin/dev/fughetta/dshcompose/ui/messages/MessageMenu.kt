package dev.fughetta.dshcompose.ui.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.chat.R
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MessageMenuRoute(
    source: StateFlow<MessageMenuState?>,
    content: @Composable (MessageMenuState) -> Unit,
) {
    val menu by source.collectAsStateWithLifecycle()
    menu?.let { content(it) }
}

@Composable
fun MessageMenu(
    state: MessageMenuState,
    onAction: (MessageOperation) -> Unit,
    onClose: () -> Unit,
    // Plugins' `message.actions`, after the built-in ones.
    extra: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onClose() },
        title = { Text(stringResource(R.string.message_actions_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionContainer {
                    Text(state.row.text.take(16000), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                for (action in MessageOperation.entries) {
                    val offered =
                        when (action) {
                            MessageOperation.Quote -> state.quoteable && state.row.text.isNotBlank()
                            MessageOperation.Branch -> state.branchCut != null
                            MessageOperation.Edit -> state.editable
                            MessageOperation.Regenerate -> state.regeneratePrompt != null
                            else -> state.row.text.isNotBlank()
                        }
                    if (offered)
                        TextButton(onClick = { onAction(action) }, enabled = !state.busy) {
                            Text(
                                stringResource(
                                    when (action) {
                                        MessageOperation.Copy -> R.string.message_action_copy
                                        MessageOperation.Share ->
                                            CoreDesignsystemR.string.message_action_share
                                        MessageOperation.Quote -> R.string.message_action_quote
                                        MessageOperation.Branch -> R.string.message_action_branch
                                        MessageOperation.Edit -> R.string.message_action_edit
                                        MessageOperation.Regenerate ->
                                            R.string.message_action_regenerate
                                    },
                                ),
                            )
                        }
                }
                extra()
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = !state.busy) {
                Text(stringResource(CoreDesignsystemR.string.close))
            }
        },
    )
}

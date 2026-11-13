package dev.fughetta.dshcompose.ui.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.collections.immutable.*

enum class PendingPromptUiPhase {
    Sending,
    Uncertain,
    Accepted,
    Rejected,
}

data class PendingPromptUi(
    val requestId: String,
    val phase: PendingPromptUiPhase,
    val text: String,
    val quote: String?,
    val attachmentNames: PersistentList<String> = persistentListOf(),
    val errorCode: String? = null,
)

data class PendingInboxUi(val item: InboxItem, val canRestore: Boolean)

data class PendingPromptsUiState(
    val owner: PendingPromptsOwner? = null,
    val pending: PersistentList<PendingPromptUi> = persistentListOf(),
    val inbox: PersistentList<PendingInboxUi> = persistentListOf(),
    val busyItem: String? = null,
    val error: String? = null,
)

data class PendingPromptsAction(val owner: PendingPromptsOwner, val command: PendingPromptsCommand)

sealed interface PendingPromptsCommand {
    data class Retry(val requestId: String) : PendingPromptsCommand

    data class RestoreRejected(val requestId: String) : PendingPromptsCommand

    data class Remove(val item: InboxItem) : PendingPromptsCommand

    data class Restore(val item: InboxItem) : PendingPromptsCommand

    data class Steer(val item: InboxItem) : PendingPromptsCommand

    data class Edit(val item: InboxItem, val text: String) : PendingPromptsCommand
}

/** Rows hidden when the panel is collapsed: local outcomes plus queued inbox items. */
internal fun PendingPromptsUiState.itemCount(): Int = pending.size + inbox.size

/** Local outcomes and the actual current InboxProjection are separate rows, keyed by stable IDs. */
@Composable
fun PendingPromptsPanel(
    state: PendingPromptsUiState,
    onAction: (PendingPromptsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = state.owner ?: return
    if (state.pending.isEmpty() && state.inbox.isEmpty() && state.error == null) return
    var editing by remember(owner) { mutableStateOf<InboxItem?>(null) }
    var removing by remember(owner) { mutableStateOf<InboxItem?>(null) }
    var expanded by rememberSaveable(owner) { mutableStateOf(true) }
    fun dispatch(command: PendingPromptsCommand) = onAction(PendingPromptsAction(owner, command))
    OutlinedCard(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 360.dp),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("heading") {
                val toggleLabel =
                    stringResource(
                        if (expanded) R.string.pending_collapse else R.string.pending_expand,
                    )
                Row(
                    Modifier.fillMaxWidth().clickable(
                        onClickLabel = toggleLabel,
                        role = Role.Button,
                    ) {
                        expanded = !expanded
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.pending_title),
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!expanded) {
                        val count = state.itemCount()
                        Text(
                            pluralStringResource(R.plurals.pending_summary, count, count),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else Spacer(Modifier.weight(1f))
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = toggleLabel,
                        modifier = Modifier.size(20.dp).rotate(if (expanded) 270f else 90f),
                    )
                }
            }
            state.error?.let { item("error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!expanded) return@LazyColumn
            items(state.pending, key = { "local:${it.requestId}" }, contentType = { "local" }) { row
                ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            when (row.phase) {
                                PendingPromptUiPhase.Sending -> R.string.pending_sending
                                PendingPromptUiPhase.Uncertain -> R.string.pending_uncertain
                                PendingPromptUiPhase.Accepted -> R.string.pending_accepted
                                PendingPromptUiPhase.Rejected -> R.string.pending_rejected
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(row.text.take(1200), style = MaterialTheme.typography.bodySmall)
                    if (row.quote != null)
                        Text(
                            stringResource(R.string.pending_quote, row.quote.take(300)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (row.attachmentNames.isNotEmpty())
                        Text(
                            stringResource(
                                R.string.pending_attachments,
                                row.attachmentNames.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    row.errorCode?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (row.phase == PendingPromptUiPhase.Uncertain) {
                        Text(
                            stringResource(R.string.pending_uncertain_help),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            { dispatch(PendingPromptsCommand.Retry(row.requestId)) },
                            enabled = state.busyItem == null,
                        ) {
                            Text(stringResource(R.string.pending_retry))
                        }
                    }
                    if (row.phase == PendingPromptUiPhase.Rejected)
                        TextButton(
                            { dispatch(PendingPromptsCommand.RestoreRejected(row.requestId)) },
                            enabled = state.busyItem == null,
                        ) {
                            Text(stringResource(R.string.pending_restore))
                        }
                    if (
                        row.phase == PendingPromptUiPhase.Sending || state.busyItem == row.requestId
                    )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            items(state.inbox, key = { "inbox:${it.item.id}" }, contentType = { "inbox" }) { entry
                ->
                val item = entry.item
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            if (item.automatic) R.string.pending_automatic
                            else if (item.slot == InboxSlot.NextStep) R.string.pending_next_step
                            else R.string.pending_next_turn,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        item.text.take(1200).ifBlank { stringResource(R.string.pending_nontext) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!item.automatic) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (entry.canRestore)
                                TextButton(
                                    { dispatch(PendingPromptsCommand.Restore(item)) },
                                    enabled = state.busyItem == null,
                                ) {
                                    Text(stringResource(R.string.pending_withdraw))
                                }
                            if (item.slot == InboxSlot.NextTurn)
                                TextButton(
                                    { dispatch(PendingPromptsCommand.Steer(item)) },
                                    enabled = state.busyItem == null,
                                ) {
                                    Text(stringResource(R.string.pending_steer))
                                }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.textOnly)
                                TextButton({ editing = item }, enabled = state.busyItem == null) {
                                    Text(stringResource(R.string.pending_edit))
                                }
                            TextButton({ removing = item }, enabled = state.busyItem == null) {
                                Text(stringResource(R.string.pending_remove))
                            }
                        }
                    }
                    if (state.busyItem == item.id) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
    editing?.let { item ->
        var text by remember(owner, item) { mutableStateOf(item.text) }
        AlertDialog(
            onDismissRequest = { if (state.busyItem == null) editing = null },
            title = { Text(stringResource(R.string.pending_edit)) },
            text = {
                OutlinedTextField(
                    text,
                    { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    label = { Text(stringResource(R.string.pending_text)) },
                )
            },
            confirmButton = {
                TextButton(
                    {
                        editing = null
                        dispatch(PendingPromptsCommand.Edit(item, text))
                    },
                    enabled = state.busyItem == null && text.isNotBlank(),
                ) {
                    Text(stringResource(R.string.pending_save))
                }
            },
            dismissButton = {
                TextButton({ editing = null }, enabled = state.busyItem == null) {
                    Text(stringResource(R.string.pending_cancel))
                }
            },
        )
    }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.pending_remove)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.pending_remove_warning))
                    Text(item.text.take(2000))
                }
            },
            confirmButton = {
                TextButton(
                    {
                        removing = null
                        dispatch(PendingPromptsCommand.Remove(item))
                    },
                    enabled = state.busyItem == null,
                ) {
                    Text(stringResource(R.string.pending_remove))
                }
            },
            dismissButton = {
                TextButton({ removing = null }) { Text(stringResource(R.string.pending_cancel)) }
            },
        )
    }
}

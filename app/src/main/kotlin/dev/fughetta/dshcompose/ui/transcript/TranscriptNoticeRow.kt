package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.chat.ChatItem

@Composable
fun TranscriptNoticeRow(
    row: ChatItem,
    onCredentials: () -> Unit,
    onModel: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var details by rememberSaveable(row.key) { mutableStateOf(false) }
    val code = row.notice?.code
    val label =
        when (row.noticeKind) {
            "aborted" -> R.string.turn_cancelled
            "retry" -> R.string.model_retry
            "max-tokens" -> R.string.transcript_limit
            "blocked" -> R.string.transcript_blocked
            "interrupted" -> R.string.transcript_restarted
            else ->
                when (code) {
                    "MISSING_CREDENTIAL",
                    "AUTH",
                    "INVALID_CREDENTIAL" -> R.string.transcript_key_needed
                    "QUOTA",
                    "ACCOUNT_QUOTA" -> R.string.transcript_quota
                    "RATE_LIMIT" -> R.string.transcript_rate_limit
                    "CONTEXT_WINDOW_EXCEEDED" -> R.string.transcript_context_limit
                    "TIMEOUT" -> R.string.transcript_timeout
                    "TRANSPORT" -> R.string.transcript_connection
                    "EMPTY_RESPONSE" -> R.string.transcript_empty
                    "MODEL_UNSUPPORTED",
                    "UNSUPPORTED_REASONING_EFFORT",
                    "CLIENT_UPGRADE_REQUIRED" -> R.string.transcript_model_refused
                    else -> R.string.turn_error
                }
        }
    Column(modifier.fillMaxWidth()) {
        Row {
            TextButton(
                { details = !details },
                Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    stringResource(label),
                    color =
                        if (row.noticeKind == "error") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.notice?.retry?.let {
                    Text(" · $it${row.notice?.maxRetries?.let {max->"/$max"}.orEmpty()}")
                }
            }
            if (code in setOf("MISSING_CREDENTIAL", "AUTH", "INVALID_CREDENTIAL"))
                TextButton(onCredentials) { Text(stringResource(R.string.transcript_credentials)) }
            if (
                code in
                    setOf(
                        "MODEL_UNSUPPORTED",
                        "UNSUPPORTED_REASONING_EFFORT",
                        "CLIENT_UPGRADE_REQUIRED",
                    )
            )
                TextButton(onModel) { Text(stringResource(R.string.transcript_choose_model)) }
            if (onRetry != null && row.noticeKind != "retry")
                TextButton(onRetry) { Text(stringResource(R.string.transcript_retry)) }
        }
        if (details && row.text.isNotBlank())
            SelectionContainer { Text(row.text, style = MaterialTheme.typography.bodySmall) }
    }
}

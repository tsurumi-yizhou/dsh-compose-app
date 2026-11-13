package dev.fughetta.dshcompose.ui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.account.formatAccountWallet
import dev.fughetta.dshcompose.domain.usage.SessionUsage
import dev.fughetta.dshcompose.domain.usage.formatUsagePercent
import dev.fughetta.dshcompose.domain.usage.formatUsageTokens
import dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont
import dev.fughetta.dshcompose.ui.clearCredentialInput
import dev.fughetta.dshcompose.ui.rememberCredentialInput
import dev.fughetta.dshcompose.ui.takeCredentialInput

@Composable
fun TranscriptContextRing(usage: SessionUsage, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val fraction = usage.contextFraction ?: return
    val color =
        if (fraction > .85) MaterialTheme.colorScheme.error
        else if (fraction >= .6) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.primary
    TextButton(onOpen, modifier, contentPadding = PaddingValues(6.dp)) {
        CircularProgressIndicator(
            progress = { fraction.toFloat().coerceAtLeast(.02f) },
            Modifier.size(16.dp),
            color = color,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            formatUsagePercent(fraction),
            color = color,
            fontFamily = LocalAppearanceCodeFont.current,
        )
    }
}

@Composable
fun TranscriptBalanceBanner(
    state: UsageState,
    onAction: (UsageAction) -> Unit,
    onTopUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.banner) return
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (state.balance?.available == false) R.string.transcript_unavailable
                        else R.string.transcript_low,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                state.balance?.let {
                    Text("${it.symbol}${formatAccountWallet(it.total.toPlainString())}")
                }
            }
            TextButton(onTopUp) { Text(stringResource(R.string.transcript_top_up)) }
            TextButton({ onAction(UsageAction.DismissBalance) }) {
                Text(stringResource(R.string.transcript_dismiss))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranscriptUsageSheet(state: UsageState, onAction: (UsageAction) -> Unit, onTopUp: () -> Unit) {
    if (!state.open) return
    LifecycleStartEffect(state.hostKey, state.sessionId) {
        onAction(UsageAction.Visibility(true))
        onStopOrDispose { onAction(UsageAction.Visibility(false)) }
    }
    var keyDialog by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onAction(UsageAction.Close) }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Text(
                    stringResource(R.string.transcript_usage),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton({ onAction(UsageAction.Refresh) }) {
                    Text(stringResource(R.string.transcript_refresh))
                }
            }
            Text(
                stringResource(R.string.transcript_context),
                style = MaterialTheme.typography.titleMedium,
            )
            val usage = state.usage
            usage.contextFraction?.let { fraction ->
                Text(
                    "${formatUsagePercent(fraction)} · ${formatUsageTokens(usage.contextTokens?:0)} / ${formatUsageTokens(usage.contextWindow?:0)}",
                )
                LinearProgressIndicator(progress = { fraction.toFloat() }, Modifier.fillMaxWidth())
            } ?: Text(stringResource(R.string.transcript_unknown))
            RowLabel(R.string.transcript_system, usage.systemTokens)
            RowLabel(R.string.transcript_tool_tokens, usage.toolsTokens)
            RowLabel(R.string.transcript_message_tokens, usage.messageTokens)
            HorizontalDivider()
            Text(
                stringResource(R.string.transcript_tokens),
                style = MaterialTheme.typography.titleMedium,
            )
            RowLabel(R.string.transcript_input, usage.input)
            RowLabel(R.string.transcript_uncached, usage.uncachedInput)
            RowLabel(R.string.transcript_cache_read, usage.cacheRead)
            RowLabel(R.string.transcript_cache_write, usage.cacheWrite)
            RowLabel(R.string.transcript_output, usage.output)
            usage.cacheHitRate?.let {
                Text("${stringResource(R.string.transcript_cache_hit)} · ${formatUsagePercent(it)}")
            }
            usage.llmMs?.let {
                Text(
                    "${stringResource(R.string.transcript_model_time)} · ${transcriptSpanTime(it)}",
                )
            }
            usage.toolMs?.let {
                Text("${stringResource(R.string.transcript_tool_time)} · ${transcriptSpanTime(it)}")
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.transcript_balance),
                style = MaterialTheme.typography.titleMedium,
            )
            state.balance?.let { balance ->
                Text(
                    "${balance.symbol}${formatAccountWallet(balance.total.toPlainString())}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = LocalAppearanceCodeFont.current,
                )
                balance.paid?.let {
                    RowLabelText(
                        stringResource(R.string.transcript_balance_paid),
                        "${balance.symbol}${formatAccountWallet(it.toPlainString())}",
                    )
                }
                balance.granted?.let {
                    RowLabelText(
                        stringResource(R.string.transcript_balance_granted),
                        "${balance.symbol}${formatAccountWallet(it.toPlainString())}",
                    )
                }
            }
                ?: Text(
                    stringResource(
                        when (state.balanceStatus) {
                            "invalid-key" -> R.string.transcript_balance_invalid
                            "unavailable" -> R.string.transcript_balance_error
                            else -> R.string.transcript_unknown
                        },
                    ),
                )
            if (state.balanceLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                TextButton(onTopUp) { Text(stringResource(R.string.transcript_top_up)) }
                TextButton({ keyDialog = true }) {
                    Text(stringResource(R.string.transcript_check_key))
                }
            }
            Text(
                stringResource(R.string.transcript_reminder),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 5, 10, 20, 50).forEach { amount ->
                    FilterChip(
                        state.threshold.compareTo(amount.toBigDecimal()) == 0,
                        { onAction(UsageAction.Threshold(amount.toBigDecimal())) },
                        label = {
                            Text(
                                if (amount == 0) stringResource(R.string.transcript_no_reminder)
                                else "${state.balance?.symbol?:"¥"}$amount",
                            )
                        },
                    )
                }
            }
        }
    }
    if (keyDialog) {
        val input = rememberCredentialInput(state.hostKey)
        AlertDialog(
            onDismissRequest = {
                input.clearCredentialInput()
                keyDialog = false
            },
            title = { Text(stringResource(R.string.transcript_check_key)) },
            text = {
                Column {
                    Text(stringResource(R.string.transcript_key_memory))
                    OutlinedSecureTextField(input, label = { Text("API key") })
                }
            },
            confirmButton = {
                TextButton(
                    {
                        input.takeCredentialInput()?.let { onAction(UsageAction.CheckKey(it)) }
                        keyDialog = false
                    },
                ) {
                    Text(stringResource(R.string.transcript_refresh))
                }
            },
            dismissButton = {
                TextButton(
                    {
                        input.clearCredentialInput()
                        keyDialog = false
                    },
                ) {
                    Text(stringResource(R.string.transcript_close))
                }
            },
        )
    }
}

@Composable
private fun RowLabel(label: Int, value: Long?) {
    if (value != null) RowLabelText(stringResource(label), formatUsageTokens(value))
}

@Composable
private fun RowLabelText(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = LocalAppearanceCodeFont.current)
    }
}

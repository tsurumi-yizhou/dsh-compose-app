package dev.fughetta.dshcompose.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.account.AccountSignInPhase
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountBalance
import dev.fughetta.dshcompose.domain.account.formatAccountWallet
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.ReadableWidth
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.MetricTile
import dev.fughetta.dshcompose.ui.adaptive.MetricTone
import dev.fughetta.dshcompose.ui.adaptive.MetricsBoard
import dev.fughetta.dshcompose.ui.adaptive.ProblemPanel
import dev.fughetta.dshcompose.ui.adaptive.ProblemSeverity
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeepSeekAccountScreen(state: DeepSeekAccountState, onAction: (DeepSeekAccountAction) -> Unit) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_deepseek)) },
                navigationIcon = { UpButton() },
                actions = {
                    if (state.phase == DeepSeekAccountPhase.SignedIn)
                        ActionIcon(
                            R.drawable.ic_refresh,
                            stringResource(R.string.account_refresh),
                            enabled = state.connected,
                        ) {
                            onAction(DeepSeekAccountAction.Refresh)
                        }
                },
            )
        },
    ) { padding ->
        val wide = windowTier().atLeastExpanded
        // Wide screens get the form measure and a balance board; phones keep the reading column.
        CenteredContent(
            modifier =
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            maxContentWidth = if (wide) ContentWidths.Form else ReadableWidth,
            verticalPadding = Spacing.Large,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                if (!state.connected)
                    Text(
                        stringResource(R.string.account_local_engine),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                state.error?.let { code ->
                    if (wide)
                        ProblemPanel(
                            title = stringResource(R.string.account_problem_title),
                            message = accountFailure(code),
                            code = code,
                            primaryLabel = stringResource(R.string.account_refresh),
                            onPrimary = { onAction(DeepSeekAccountAction.Refresh) },
                        )
                    else Text(accountFailure(code), color = MaterialTheme.colorScheme.error)
                }
                when (state.phase) {
                    DeepSeekAccountPhase.Unknown -> Text(stringResource(R.string.account_waiting))
                    DeepSeekAccountPhase.SignedOut ->
                        Text(stringResource(R.string.account_signed_out))
                    DeepSeekAccountPhase.SigningIn -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(
                                when (state.attempt?.phase) {
                                    AccountSignInPhase.WaitingBrowser ->
                                        R.string.account_waiting_browser
                                    AccountSignInPhase.Exchanging,
                                    AccountSignInPhase.Committing -> R.string.account_finishing
                                    else -> R.string.account_preparing
                                },
                            ),
                        )
                        if (state.browserReturned)
                            Text(stringResource(R.string.account_browser_returned))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.spacedBy(Spacing.Small, Alignment.End),
                        ) {
                            TextButton(onClick = { onAction(DeepSeekAccountAction.Cancel) }) {
                                Text(stringResource(R.string.account_cancel))
                            }
                            if (state.attempt?.authorizeUrl != null)
                                OutlinedButton(
                                    onClick = { onAction(DeepSeekAccountAction.ReopenBrowser) },
                                ) {
                                    Text(stringResource(R.string.account_reopen))
                                }
                        }
                    }
                    DeepSeekAccountPhase.SignedIn -> {
                        val profile = state.profile
                        Text(
                            profile?.name?.takeIf { it.isNotBlank() }
                                ?: profile?.contact
                                ?: stringResource(R.string.account_deepseek),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (!profile?.name.isNullOrBlank() && !profile.contact.isNullOrBlank())
                            Text(profile.contact.orEmpty())
                        if (state.profileError)
                            Text(
                                stringResource(R.string.account_profile_failed),
                                color = MaterialTheme.colorScheme.error,
                            )
                        if (wide) AccountWalletBoard(state.balance, state.balanceError)
                        else AccountWalletSection(state.balance, state.balanceError)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                        ) {
                            Button(
                                onClick = { onAction(DeepSeekAccountAction.TopUp) },
                                enabled = state.connected && state.topUpUrl != null,
                            ) {
                                Text(stringResource(R.string.account_top_up))
                            }
                            OutlinedButton(
                                onClick = { onAction(DeepSeekAccountAction.Usage) },
                                enabled = state.connected && state.usageUrl != null,
                            ) {
                                Text(stringResource(R.string.account_usage))
                            }
                        }
                    }
                }
                if (
                    state.phase in
                        setOf(DeepSeekAccountPhase.Unknown, DeepSeekAccountPhase.SignedOut)
                )
                    Button(
                        onClick = { onAction(DeepSeekAccountAction.SignIn) },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(R.string.account_sign_in))
                    }
                HorizontalDivider()
                Text(
                    stringResource(R.string.account_key_alternative),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { onAction(DeepSeekAccountAction.ApiKeys) }) {
                    Text(stringResource(R.string.account_manage_keys))
                }
                if (state.phase == DeepSeekAccountPhase.SignedIn) {
                    HorizontalDivider()
                    TextButton(
                        onClick = { onAction(DeepSeekAccountAction.RequestSignOut) },
                        enabled = state.connected && !state.busy,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text(stringResource(R.string.account_sign_out))
                    }
                }
            }
        }
    }
    if (state.confirmSignOut)
        AlertDialog(
            onDismissRequest = { if (!state.busy) onAction(DeepSeekAccountAction.DismissSignOut) },
            title = { Text(stringResource(R.string.account_sign_out)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        stringResource(
                            when (state.runningAccountTasks) {
                                true -> R.string.account_sign_out_running
                                false -> R.string.account_sign_out_body
                                null -> R.string.account_sign_out_unknown
                            },
                        ),
                    )
                    state.error?.let {
                        Text(accountFailure(it), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(DeepSeekAccountAction.ConfirmSignOut) },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.account_sign_out))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(DeepSeekAccountAction.DismissSignOut) },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
}

@Composable
fun AccountWalletSection(balance: DeepSeekAccountBalance?, failed: Boolean = false) {
    if (failed) {
        Text(
            stringResource(R.string.account_balance_failed),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    balance ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(
            stringResource(R.string.account_recharge_balance),
            style = MaterialTheme.typography.titleSmall,
        )
        balance.recharge.forEach {
            Text(
                "${it.currency} ${formatAccountWallet(it.balance)}",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (balance.bonus.isNotEmpty()) {
            Text(
                stringResource(R.string.account_bonus_balance),
                style = MaterialTheme.typography.titleSmall,
            )
            balance.bonus.forEach { Text("${it.currency} ${formatAccountWallet(it.balance)}") }
        }
    }
}

/** Balance and bonus as metric tiles; a failed refresh keeps its own warning card. */
@Composable
private fun AccountWalletBoard(balance: DeepSeekAccountBalance?, failed: Boolean) {
    if (failed) {
        ProblemPanel(
            title = stringResource(R.string.account_balance_failed),
            severity = ProblemSeverity.Warning,
        )
        return
    }
    balance ?: return
    val recharge = stringResource(R.string.account_recharge_balance)
    val bonus = stringResource(R.string.account_bonus_balance)
    val tiles = buildList {
        balance.recharge.forEach {
            add(
                MetricTile(
                    label = recharge,
                    value = formatAccountWallet(it.balance),
                    unit = it.currency,
                    tone = MetricTone.Positive,
                ),
            )
        }
        balance.bonus.forEach {
            add(
                MetricTile(
                    label = bonus,
                    value = formatAccountWallet(it.balance),
                    unit = it.currency,
                ),
            )
        }
    }
    MetricsBoard(tiles)
}

@Composable
private fun accountFailure(code: String): String =
    stringResource(
        when (code.substringAfterLast('/')) {
            "no-response" -> R.string.account_offline
            "network" -> R.string.account_service_error
            "protocol" -> R.string.account_rejected
            "expired",
            "engine-timeout" -> R.string.account_timeout
            "storage" -> R.string.account_storage_error
            "interrupted" -> R.string.account_interrupted
            "browser-unavailable" -> R.string.account_browser_unavailable
            else -> R.string.account_failed
        },
    )

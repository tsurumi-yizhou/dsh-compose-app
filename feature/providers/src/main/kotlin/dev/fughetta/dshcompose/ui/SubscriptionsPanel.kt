package dev.fughetta.dshcompose.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.providers.LoginPhase
import dev.fughetta.dshcompose.domain.providers.Subscription
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.providers.*
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun SubscriptionsPanel(state: SubscriptionsUiState, onAction: (AppAction) -> Unit) {
    var selected by remember { mutableStateOf<Subscription?>(null) }
    Column {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        for (subscription in Subscription.entries) {
            val status = state.statuses.firstOrNull { it.subscription == subscription }
            ProviderNavigationRow(
                title = subscription.label,
                status =
                    stringResource(
                        when {
                            !state.connected -> CoreDesignsystemR.string.engine_not_connected
                            status == null -> R.string.provider_status_unknown
                            status.signedIn -> R.string.subscription_signed_in
                            else -> R.string.subscription_signed_out
                        },
                    ),
                active = status?.signedIn == true,
                enabled =
                    state.connected && !state.loading && !state.writing && state.login == null,
                onClick = { selected = subscription },
            )
            if (subscription != Subscription.entries.last())
                dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider(16.dp)
        }
    }
    selected?.let { subscription ->
        val status = state.statuses.firstOrNull { it.subscription == subscription }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(subscription.label) },
            text = {
                Text(
                    status?.account
                        ?: stringResource(
                            if (status?.signedIn == true) R.string.subscription_signed_in
                            else R.string.subscription_signed_out,
                        ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled =
                        state.connected && !state.loading && !state.writing && state.login == null,
                    onClick = {
                        selected = null
                        onAction(
                            if (status?.signedIn == true)
                                AppAction.RequestSubscriptionSignOut(subscription)
                            else AppAction.BeginSubscriptionLogin(subscription),
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            if (status?.signedIn == true) R.string.subscription_sign_out
                            else R.string.subscription_sign_in,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(CoreDesignsystemR.string.close))
                }
            },
        )
    }
}

@Composable
internal fun SubscriptionDialogs(state: SubscriptionsUiState, onAction: (AppAction) -> Unit) {
    state.login?.let { SubscriptionLoginDialog(it, onAction) }
    state.signOut?.let { subscription ->
        AlertDialog(
            onDismissRequest = { onAction(AppAction.DismissSubscriptionSignOut) },
            title = {
                Text(stringResource(R.string.subscription_sign_out_title, subscription.label))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.subscription_sign_out_details))
                    if (state.writing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(AppAction.ConfirmSubscriptionSignOut) },
                    enabled = !state.writing,
                ) {
                    Text(stringResource(R.string.subscription_sign_out))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(AppAction.DismissSubscriptionSignOut) },
                    enabled = !state.writing,
                ) {
                    Text(stringResource(CoreDesignsystemR.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionLoginDialog(state: SubscriptionLoginState, onAction: (AppAction) -> Unit) {
    val input = rememberCredentialInput(state.nonce, state.step.phase)
    val uriHandler = LocalUriHandler.current
    val activity = LocalActivity.current
    var browserFailed by remember(state.nonce) { mutableStateOf(false) }
    val open: () -> Unit = {
        val url = state.step.url
        browserFailed =
            if (url != null && validLoginBrowserUrl(url)) {
                runCatching { uriHandler.openUri(url) }
                    .isFailure
                    .also { if (!it) onAction(AppAction.SubscriptionBrowserOpened(state.nonce)) }
            } else true
    }
    LaunchedEffect(state.step.phase, state.step.url) {
        if (state.step.phase == LoginPhase.Open && !state.browserOpened) open()
    }
    DisposableEffect(state.nonce, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true)
                onAction(AppAction.CloseSubscriptionLogin(state.nonce))
        }
    }
    val close = { onAction(AppAction.CloseSubscriptionLogin(state.nonce)) }
    Dialog(
        onDismissRequest = close,
        properties =
            DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(state.subscription.label) },
                    actions = {
                        TextButton(onClick = close) {
                            Text(stringResource(CoreDesignsystemR.string.close))
                        }
                    },
                )
            },
        ) { padding ->
            ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = inset, vertical = Spacing.Large),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Large),
                ) {
                    Text(
                        stringResource(
                            R.string.subscription_sign_in_title,
                            state.subscription.label,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(R.string.providers_authorization_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (state.step.phase) {
                        LoginPhase.Starting -> Text(stringResource(R.string.subscription_preparing))
                        LoginPhase.Open,
                        LoginPhase.Device -> {
                            Text(stringResource(R.string.subscription_finish_browser))
                            state.step.userCode?.let { code ->
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    SelectionContainer {
                                        Text(
                                            code,
                                            Modifier.padding(Spacing.Large),
                                            style = MaterialTheme.typography.headlineMedium,
                                        )
                                    }
                                }
                            }
                            Button(onClick = open, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.subscription_open_browser))
                            }
                            if (browserFailed)
                                Text(
                                    stringResource(R.string.subscription_browser_failed),
                                    color = MaterialTheme.colorScheme.error,
                                )
                        }
                        LoginPhase.Prompt -> {
                            state.step.message?.let { Text(it) }
                            OutlinedSecureTextField(
                                state = input,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                                textObfuscationMode = TextObfuscationMode.Hidden,
                                label = {
                                    Text(stringResource(R.string.subscription_authorization_input))
                                },
                            )
                            Button(
                                onClick = {
                                    input.takeCredentialInput()?.let {
                                        onAction(AppAction.AnswerSubscriptionLogin(it))
                                    }
                                },
                                enabled = !state.busy && input.text.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(CoreDesignsystemR.string.confirm))
                            }
                        }
                        LoginPhase.Done -> {
                            Text(
                                stringResource(R.string.subscription_signed_in),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Button(onClick = close, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(CoreDesignsystemR.string.confirm))
                            }
                        }
                        LoginPhase.Failed ->
                            Text(stringResource(R.string.subscription_login_failed))
                        LoginPhase.Cancelled ->
                            Text(stringResource(R.string.subscription_cancelled))
                    }
                    if (state.busy || state.step.phase == LoginPhase.Starting)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (
                        state.step.phase in setOf(LoginPhase.Failed, LoginPhase.Cancelled) ||
                            state.error != null
                    ) {
                        FilledTonalButton(
                            onClick = { onAction(AppAction.RetrySubscriptionLogin) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.subscription_retry))
                        }
                    }
                }
            }
        }
    }
}

internal fun validLoginBrowserUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null
}
    .getOrDefault(false)

@Composable
internal fun SubscriptionsPanelRoute(
    source: StateFlow<SubscriptionsUiState>,
    onAction: (AppAction) -> Unit,
) {
    SubscriptionsPanel(localizedSubscriptions(source.collectAsStateWithLifecycle().value), onAction)
}

@Composable
internal fun SubscriptionDialogsRoute(
    source: StateFlow<SubscriptionsUiState>,
    onAction: (AppAction) -> Unit,
) {
    SubscriptionDialogs(
        localizedSubscriptions(source.collectAsStateWithLifecycle().value),
        onAction,
    )
}

@Composable
private fun localizedSubscriptions(state: SubscriptionsUiState) =
    state.copy(
        error = subscriptionMessage(state.error),
        login = state.login?.let { it.copy(error = subscriptionMessage(it.error)) },
    )

@Composable
private fun subscriptionMessage(code: String?): String? = code?.let {
    stringResource(
        when (it) {
            "subscriptions/subscription_answer_failed" -> R.string.subscription_answer_failed
            "subscriptions/subscription_cancel_failed" -> R.string.subscription_cancel_failed
            "subscriptions/subscription_login_failed" -> R.string.subscription_login_failed
            "subscriptions/subscription_poll_failed" -> R.string.subscription_poll_failed
            "subscriptions/subscription_sign_out_failed" -> R.string.subscription_sign_out_failed
            "subscriptions/subscriptions_read_failed" -> R.string.subscriptions_read_failed
            else -> R.string.subscriptions_read_failed
        },
    )
}

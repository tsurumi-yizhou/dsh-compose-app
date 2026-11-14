package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.feature.providers.R
import dev.fughetta.dshcompose.ui.account.DeepSeekAccountPhase
import dev.fughetta.dshcompose.ui.account.DeepSeekAccountState
import dev.fughetta.dshcompose.ui.adaptive.SettingsCardGroup
import dev.fughetta.dshcompose.ui.adaptive.SettingsRow
import dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider
import dev.fughetta.dshcompose.ui.providers.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidersScreen(
    state: ProvidersUiState,
    subscriptions: kotlinx.coroutines.flow.StateFlow<SubscriptionsUiState>,
    account: DeepSeekAccountState,
    onAction: (AppAction) -> Unit,
) {
    var selectedAccount by rememberSaveable { mutableStateOf<String?>(null) }
    val accounts =
        state.accounts.filterNot {
            it.id == "builtin-deepseek" && state.settings?.find("deepseek-api") != null
        }
    val profiles =
        remember(state.settings) {
            state.settings?.profiles?.associateBy { "custom/${it.route}" }.orEmpty()
        }
    LifecycleStartEffect(Unit) {
        onAction(AppAction.ObserveProviders(true))
        onAction(AppAction.ObserveSubscriptions(true))
        onStopOrDispose {
            onAction(AppAction.ObserveProviders(false))
            onAction(AppAction.ObserveSubscriptions(false))
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreDesignsystemR.string.providers)) },
                navigationIcon = { UpButton() },
                actions = {
                    ActionIcon(
                        CoreDesignsystemR.drawable.ic_refresh,
                        stringResource(CoreDesignsystemR.string.refresh),
                        enabled = state.connected && !state.loading && !state.writing,
                    ) {
                        onAction(AppAction.RefreshProviders)
                        onAction(AppAction.RefreshSubscriptions)
                        onAction(
                            AppAction.DeepSeekAccount(
                                dev.fughetta.dshcompose.ui.account.DeepSeekAccountAction.Refresh,
                            ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.connected && state.settings?.writable == true && !state.writing)
                ExtendedFloatingActionButton(
                    modifier = Modifier.padding(bottom = Spacing.Medium),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = { onAction(AppAction.OpenProviderEditor()) },
                    icon = { Icon(painterResource(CoreDesignsystemR.drawable.ic_add), null) },
                    text = { Text(stringResource(R.string.add_provider)) },
                )
        },
    ) { padding ->
        ReadablePane(Modifier.fillMaxSize().padding(padding)) { inset ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(start = inset, end = inset, top = Spacing.Large, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                if (!state.connected)
                    item { Text(stringResource(CoreDesignsystemR.string.engine_not_connected)) }
                if (state.loading || state.writing)
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                state.notice?.let { message -> item { Text(message) } }
                item {
                    SettingsCardGroup(title = stringResource(R.string.providers_accounts)) {
                        ProviderNavigationRow(
                            title = stringResource(R.string.account_deepseek),
                            status =
                                stringResource(
                                    when (account.phase) {
                                        DeepSeekAccountPhase.SignedIn ->
                                            R.string.subscription_signed_in
                                        DeepSeekAccountPhase.SigningIn ->
                                            R.string.account_waiting_browser
                                        else -> R.string.subscription_signed_out
                                    },
                                ),
                            active = account.phase == DeepSeekAccountPhase.SignedIn,
                            onClick = { onAction(AppAction.OpenDeepSeekAccount) },
                        )
                        SettingsRowDivider(16.dp)
                        SubscriptionsPanelRoute(subscriptions, onAction)
                    }
                }
                item {
                    SettingsCardGroup(title = stringResource(R.string.providers_api)) {
                        if (state.settings?.find("magpie") == null) {
                            ProviderNavigationRow(
                                title = "Magpie",
                                status = stringResource(R.string.provider_status_unconfigured),
                                active = false,
                                enabled =
                                    state.connected &&
                                        state.settings?.writable == true &&
                                        !state.writing,
                                onClick = { onAction(AppAction.OpenMagpieProvider) },
                            )
                            if (accounts.isNotEmpty()) SettingsRowDivider(16.dp)
                        }
                        accounts.forEachIndexed { index, provider ->
                            val status = provider.credentialRef?.let { state.credentials[it] }
                            ProviderNavigationRow(
                                title = provider.name,
                                status =
                                    stringResource(
                                        when (status?.configured) {
                                            true -> R.string.provider_status_configured
                                            false -> R.string.provider_status_unconfigured
                                            null -> R.string.provider_status_unknown
                                        },
                                    ),
                                active = status?.configured == true,
                                onClick = {
                                    when (provider.id) {
                                        "builtin-deepseek",
                                        "custom/deepseek-api" ->
                                            onAction(AppAction.OpenDeepSeekProvider)
                                        "custom/magpie" -> onAction(AppAction.OpenMagpieProvider)
                                        else -> selectedAccount = provider.id
                                    }
                                },
                            )
                            if (index != accounts.lastIndex) SettingsRowDivider(16.dp)
                        }
                    }
                }
                item {
                    Text(
                        text =
                            buildAnnotatedString {
                                withLink(
                                    LinkAnnotation.Url(
                                        "https://usemagpie.ai/",
                                        styles =
                                            TextLinkStyles(
                                                style =
                                                    SpanStyle(
                                                        color =
                                                            MaterialTheme.colorScheme
                                                                .onSurfaceVariant,
                                                        textDecoration = TextDecoration.Underline,
                                                    ),
                                            ),
                                    ),
                                ) {
                                    append(stringResource(R.string.magpie_website))
                                }
                            },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    state.accounts
        .firstOrNull { it.id == selectedAccount }
        ?.let { account ->
            val ref = account.credentialRef
            val status = ref?.let { state.credentials[it] }
            val detailAction: (AppAction) -> Unit = {
                selectedAccount = null
                onAction(it)
            }
            AlertDialog(
                onDismissRequest = { selectedAccount = null },
                title = { Text(account.name) },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                    ) {
                        if (ref == null) Text(stringResource(R.string.provider_no_key_reference))
                        else {
                            Text(
                                stringResource(
                                    when (status?.configured) {
                                        true -> R.string.credential_configured
                                        false -> R.string.credential_not_configured
                                        null -> R.string.credential_status_unknown
                                    },
                                ),
                            )
                            if (status?.writable == false)
                                Text(stringResource(R.string.credential_read_only))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        detailAction(AppAction.ManageProviderKey(account))
                                    },
                                    enabled =
                                        state.connected &&
                                            status?.writable == true &&
                                            !state.writing,
                                ) {
                                    Text(stringResource(R.string.configure_credential))
                                }
                                if (status?.configured == true)
                                    TextButton(
                                        onClick = {
                                            detailAction(
                                                AppAction.ManageProviderKey(
                                                    account,
                                                    removing = true,
                                                ),
                                            )
                                        },
                                        enabled =
                                            state.connected && status.writable && !state.writing,
                                    ) {
                                        Text(stringResource(CoreDesignsystemR.string.remove))
                                    }
                            }
                        }
                        val profile = profiles[account.id]
                        if (profile != null && state.settings?.writable == true)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                            ) {
                                TextButton(
                                    onClick = {
                                        detailAction(AppAction.OpenProviderEditor(profile.route))
                                    },
                                    enabled = state.connected && !state.writing,
                                ) {
                                    Text(stringResource(R.string.edit_provider))
                                }
                                if (requireNotNull(state.settings).removable(profile.route))
                                    TextButton(
                                        onClick = {
                                            detailAction(
                                                AppAction.OpenProviderEditor(
                                                    profile.route,
                                                    removing = true,
                                                ),
                                            )
                                        },
                                        enabled = state.connected && !state.writing,
                                    ) {
                                        Text(stringResource(R.string.remove_provider))
                                    }
                            }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedAccount = null }) {
                        Text(stringResource(CoreDesignsystemR.string.close))
                    }
                },
            )
        }
    state.dialog?.let { CredentialDialog(it, onAction) }
    state.editor?.let {
        if (it.endpointOnly) ProviderEndpointDialog(it, onAction)
        else ProviderEditorDialog(it, onAction)
    }
    SubscriptionDialogsRoute(subscriptions, onAction)
}

@Composable
internal fun ProviderNavigationRow(
    title: String,
    status: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        enabled = enabled,
        chevron = true,
        onClick = onClick,
        trailingContent = {
            Row(
                modifier = Modifier.widthIn(max = 144.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Badge(
                    containerColor =
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialDialog(state: CredentialDialogState, onAction: (AppAction) -> Unit) {
    val input = rememberCredentialInput(state.ref, state.removing)
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!state.busy) onAction(AppAction.CloseProviderKeyDialog) },
        title = {
            Text(
                stringResource(
                    if (state.removing) R.string.remove_credential
                    else R.string.configure_credential,
                ),
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                Text(state.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        if (state.removing) R.string.remove_credential_details
                        else R.string.credential_storage_details,
                    ),
                )
                if (!state.removing)
                    OutlinedSecureTextField(
                        state = input,
                        enabled = !state.busy,
                        textObfuscationMode = TextObfuscationMode.Hidden,
                        label = { Text(stringResource(R.string.api_key)) },
                    )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (state.removing) onAction(AppAction.ConfirmRemoveProviderKey)
                    else if (input.text.isNotBlank()) {
                        input.takeCredentialInput()?.let {
                            onAction(AppAction.SubmitProviderKey(it))
                        }
                    }
                },
                enabled = !state.busy && (state.removing || input.text.isNotBlank()),
            ) {
                Text(stringResource(CoreDesignsystemR.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(AppAction.CloseProviderKeyDialog) },
                enabled = !state.busy,
            ) {
                Text(stringResource(CoreDesignsystemR.string.cancel))
            }
        },
    )
}

@Composable
fun ProvidersRoute(
    source: kotlinx.coroutines.flow.StateFlow<ProvidersUiState>,
    subscriptions: kotlinx.coroutines.flow.StateFlow<SubscriptionsUiState>,
    account: DeepSeekAccountState,
    onAction: (AppAction) -> Unit,
) {
    val state = source.collectAsStateWithLifecycle().value
    val localized =
        state.copy(
            error = providerMessage(state.error),
            notice = providerMessage(state.notice),
            dialog = state.dialog?.let { it.copy(error = providerMessage(it.error)) },
            editor =
                state.editor?.let { editor ->
                    editor.copy(
                        error = providerMessage(editor.error),
                        catalogError = providerMessage(editor.catalogError),
                        discovery =
                            editor.discovery?.let { it.copy(error = providerMessage(it.error)) },
                    )
                },
        )
    ProvidersScreen(localized, subscriptions, account, onAction)
}

@Composable
private fun providerMessage(code: String?): String? = code?.let {
    stringResource(
        when (it) {
            "providers/credential_status_unconfirmed" -> R.string.credential_status_unconfirmed
            "providers/credential_updated" -> R.string.credential_updated
            "providers/endpoint-key-required" -> R.string.endpoint_key_required
            "providers/credential_write_failed" -> R.string.credential_write_failed
            "providers/provider_catalog_failed" -> R.string.provider_catalog_failed
            "providers/provider_discovery_failed" -> R.string.provider_discovery_failed
            "providers/provider_profile_conflict" -> R.string.provider_profile_conflict
            "providers/provider_profile_status_unconfirmed" ->
                R.string.provider_profile_status_unconfirmed
            "providers/provider_profile_updated" -> R.string.provider_profile_updated
            "providers/provider_profile_write_failed" -> R.string.provider_profile_write_failed
            "providers/providers_load_failed" -> R.string.providers_load_failed
            else -> R.string.providers_load_failed
        },
    )
}

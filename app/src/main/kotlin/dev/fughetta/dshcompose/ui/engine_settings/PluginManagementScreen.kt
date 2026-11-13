package dev.fughetta.dshcompose.ui.engine_settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.plugins.PluginBundle
import dev.fughetta.dshcompose.domain.sharing.SharedPlugin
import dev.fughetta.dshcompose.domain.sharing.sharedPluginFromBundle
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.RadioListItem
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.EmptyStatePanel
import dev.fughetta.dshcompose.ui.adaptive.LazyAdaptiveGrid
import dev.fughetta.dshcompose.ui.adaptive.ProblemPanel
import dev.fughetta.dshcompose.ui.adaptive.WindowTier
import dev.fughetta.dshcompose.ui.adaptive.windowTier

/** Which installed plugins the toolbar keeps: every card, enabled ones, or disabled ones. */
internal const val InstalledFilterAll = 0
internal const val InstalledFilterEnabled = 1
internal const val InstalledFilterDisabled = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagementScreen(
    state: PluginManagementState,
    onAction: (PluginManagementAction) -> Unit,
    onConfigure: (String) -> Unit,
    modifier: Modifier = Modifier,
    onShare: (SharedPlugin) -> Unit = {},
    onCordis: () -> Unit = {},
) {
    LifecycleStartEffect(Unit) {
        onAction(PluginManagementAction.Observe(true))
        onStopOrDispose { onAction(PluginManagementAction.Observe(false)) }
    }
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(InstalledFilterAll) }
    var detailName by rememberSaveable { mutableStateOf<String?>(null) }
    val needle = query.trim()
    val bundles =
        remember(state.bundles, needle, filter, locale) {
            state.bundles.filter { bundle ->
                val title = bundle.meta?.title?.resolve(locale) ?: bundle.name
                val matchesFilter =
                    when (filter) {
                        InstalledFilterEnabled -> bundle.enabled
                        InstalledFilterDisabled -> !bundle.enabled
                        else -> true
                    }
                matchesFilter &&
                    (needle.isEmpty() ||
                        title.contains(needle, ignoreCase = true) ||
                        bundle.name.contains(needle, ignoreCase = true) ||
                        bundle.description?.contains(needle, ignoreCase = true) == true)
            }
        }
    val entries =
        remember(state.entries, needle, filter, locale) {
            state.entries.filter { entry ->
                val title = entry.meta?.title?.resolve(locale) ?: entry.moduleName
                val matchesFilter =
                    when (filter) {
                        InstalledFilterEnabled -> entry.enabled
                        InstalledFilterDisabled -> !entry.enabled
                        else -> true
                    }
                matchesFilter &&
                    (needle.isEmpty() ||
                        title.contains(needle, ignoreCase = true) ||
                        entry.moduleName.contains(needle, ignoreCase = true))
            }
        }
    Scaffold(
        modifier,
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.navigation_plugins)) },
                    navigationIcon = { UpButton() },
                    actions = {
                        if (state.canInstall && !state.busy && !state.uncertain)
                            ActionIcon(
                                R.drawable.ic_add,
                                stringResource(R.string.engine_install),
                            ) {
                                onAction(PluginManagementAction.NewInstall)
                            }
                        ActionIcon(
                            R.drawable.ic_refresh,
                            stringResource(R.string.engine_refresh),
                            enabled = !state.busy,
                        ) {
                            onAction(PluginManagementAction.Refresh)
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = if (state.marketTab) 1 else 0) {
                    Tab(
                        !state.marketTab,
                        { onAction(PluginManagementAction.Tab(false)) },
                        text = { Text(stringResource(R.string.engine_installed)) },
                    )
                    Tab(
                        state.marketTab,
                        { onAction(PluginManagementAction.Tab(true)) },
                        text = { Text(stringResource(R.string.engine_market)) },
                    )
                }
            }
        },
    ) { padding ->
        val columns =
            when (windowTier()) {
                WindowTier.Expanded,
                WindowTier.Large -> 3
                WindowTier.ExtraLarge -> 4
                else -> 1
            }
        val contentPadding =
            PaddingValues(
                start = Spacing.Large,
                end = Spacing.Large,
                top = Spacing.Large,
                bottom = Spacing.ExtraLarge,
            )
        val board = Modifier.widthIn(max = ContentWidths.Board).fillMaxWidth().fillMaxHeight()
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (!state.marketTab)
                LazyAdaptiveGrid(
                    minItemWidth = PluginCardWidth,
                    modifier = board,
                    contentPadding = contentPadding,
                    spacing = Spacing.Medium,
                    maxColumns = columns,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PluginStatusHeader(state, onAction)
                    }
                    if (state.bundles.isNotEmpty() || state.entries.isNotEmpty())
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            InstalledToolbar(
                                query = query,
                                onQuery = { query = it },
                                filter = filter,
                                onFilter = { filter = it },
                            )
                        }
                    if (
                        !state.loading &&
                            !state.busy &&
                            state.error == null &&
                            state.bundles.isEmpty() &&
                            state.entries.isEmpty()
                    )
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyStatePanel(
                                title = stringResource(R.string.engine_no_plugins),
                                body = stringResource(R.string.engine_no_plugins_hint),
                            )
                        }
                    else if (
                        bundles.isEmpty() &&
                            entries.isEmpty() &&
                            !state.loading &&
                            state.error == null
                    )
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyStatePanel(
                                title = stringResource(R.string.plugin_no_match),
                                icon = R.drawable.ic_search,
                            )
                        }
                    items(bundles, key = { "bundle:${it.name}" }) { bundle ->
                        BundleCard(
                            state = state,
                            bundle = bundle,
                            locale = locale,
                            onAction = onAction,
                            onConfigure = onConfigure,
                            onShare = onShare,
                            onOpen = { detailName = bundle.name },
                        )
                    }
                    items(entries, key = { "entry:${it.entryId}" }) { entry ->
                        EntryCard(state, entry, locale, onAction, onConfigure)
                    }
                    if (state.cordisReadable)
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            OutlinedCard(onClick = onCordis, modifier = Modifier.fillMaxWidth()) {
                                ListItem(
                                    content = { Text(stringResource(R.string.cordis_title)) },
                                    supportingContent = {
                                        Text(
                                            if (state.cordis.isEmpty())
                                                stringResource(R.string.cordis_subtitle)
                                            else
                                                stringResource(
                                                    R.string.cordis_count,
                                                    state.cordis.size,
                                                    state.cordis.count { it.running },
                                                ),
                                        )
                                    },
                                    trailingContent = {
                                        Icon(painterResource(R.drawable.ic_chevron_right), null)
                                    },
                                )
                            }
                        }
                }
            else
                LazyAdaptiveGrid(
                    minItemWidth = PluginCardWidth,
                    modifier = board,
                    contentPadding = contentPadding,
                    spacing = Spacing.Medium,
                    maxColumns = columns,
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PluginStatusHeader(state, onAction)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MarketFilterCard(state, locale, onAction)
                    }
                    state.marketError?.let { code ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ProblemPanel(
                                title = stringResource(R.string.engine_market_error_title),
                                message =
                                    marketErrorText(code)?.let { stringResource(it) }
                                        ?: stringResource(R.string.engine_error_hint),
                                code = code,
                                primaryLabel = stringResource(R.string.engine_retry),
                                onPrimary = { onAction(PluginManagementAction.Refresh) },
                            )
                        }
                    }
                    if (
                        !state.marketLoading &&
                            state.marketError == null &&
                            state.marketRows.isEmpty()
                    )
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyStatePanel(
                                title = stringResource(R.string.engine_no_market_results),
                                icon = R.drawable.ic_search,
                            )
                        }
                    items(state.marketRows, key = { it.name }) { entry ->
                        MarketPluginCard(entry, locale, onAction)
                    }
                }
        }
    }
    detailName
        ?.let { name -> state.bundles.firstOrNull { it.name == name } }
        ?.let { bundle ->
            BundleDetailSheet(
                bundle = bundle,
                configs = bundle.rows.filter { it.rowId in state.configurableIds },
                locale = locale,
                shareable =
                    remember(bundle, locale) {
                        runCatching { sharedPluginFromBundle(bundle, locale) }.getOrNull()
                    },
                localSource = bundle.isLocalSource(state.local),
                busy = state.busy,
                uncertain = state.uncertain,
                onConfigure = onConfigure,
                onShare = onShare,
                onReload = {
                    onAction(PluginManagementAction.Request(PluginConfirmation.Reload(bundle.name)))
                },
                onRemove = {
                    onAction(PluginManagementAction.Request(PluginConfirmation.Remove(bundle.name)))
                },
                onDismiss = { detailName = null },
            )
        }
    state.selectedMarket?.let { name ->
        state.market
            ?.plugins
            ?.firstOrNull { it.name == name }
            ?.let { entry ->
                val selected =
                    entry.versions.find {
                        it.version ==
                            (state.selectedVersion ?: entry.versions.firstOrNull()?.version)
                    }
                val shareable =
                    remember(entry, selected, locale) {
                        selected?.let { version ->
                            runCatching {
                                SharedPlugin.installed(
                                    entry.name,
                                    version.spec,
                                    version.version,
                                    entry.title?.resolve(locale) ?: entry.title?.resolve(locale),
                                    entry.author,
                                    entry.description?.resolve(locale)
                                        ?: entry.description?.resolve(locale),
                                )
                            }
                                .getOrNull()
                        }
                    }
                AlertDialog(
                    onDismissRequest = { onAction(PluginManagementAction.SelectMarket(null)) },
                    title = {
                        Text(
                            entry.title?.resolve(locale)
                                ?: entry.title?.resolve(locale)
                                ?: entry.name,
                        )
                    },
                    text = {
                        LazyColumn {
                            item {
                                Text(
                                    entry.description?.resolve(locale)
                                        ?: entry.description?.resolve(locale).orEmpty(),
                                )
                                Text(entry.name)
                                TextButton(
                                    onClick = { shareable?.let(onShare) },
                                    enabled = shareable != null && !state.busy && selected != null,
                                ) {
                                    Text(stringResource(R.string.sharing_share))
                                }
                                if (selected != null && shareable == null)
                                    Text(
                                        stringResource(R.string.sharing_local_plugin),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                            }
                            items(entry.versions, key = { it.version }) { version ->
                                RadioListItem(
                                    version.version,
                                    selected?.version == version.version,
                                    {
                                        onAction(
                                            PluginManagementAction.SelectVersion(version.version),
                                        )
                                    },
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { onAction(PluginManagementAction.InstallSelected) },
                            enabled =
                                state.canInstall &&
                                    !state.busy &&
                                    !state.uncertain &&
                                    selected != null,
                        ) {
                            Text(stringResource(R.string.engine_install))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { onAction(PluginManagementAction.SelectMarket(null)) },
                        ) {
                            Text(stringResource(R.string.engine_back))
                        }
                    },
                )
            }
    }
    state.draft?.let { draft ->
        AlertDialog(
            onDismissRequest = { onAction(PluginManagementAction.CloseDraft) },
            title = { Text(stringResource(R.string.engine_install)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Small),
                ) {
                    OutlinedTextField(
                        draft.spec,
                        { onAction(PluginManagementAction.Spec(it)) },
                        enabled = !state.busy && !state.uncertain,
                        label = { Text(stringResource(R.string.engine_spec)) },
                    )
                    OutlinedTextField(
                        draft.registry,
                        { onAction(PluginManagementAction.Registry(it)) },
                        enabled = !state.busy && !state.uncertain,
                        label = { Text(stringResource(R.string.engine_registry)) },
                    )
                    draft.inspection?.let {
                        Text(
                            listOfNotNull(
                                    it.name,
                                    it.version,
                                    it.description,
                                    it.problem,
                                    it.reason,
                                )
                                .joinToString("\n"),
                        )
                    }
                    TextButton(
                        onClick = { onAction(PluginManagementAction.Inspect) },
                        enabled = !state.busy && !state.uncertain && draft.spec.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.engine_inspect))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(PluginManagementAction.RequestInstall) },
                    enabled = !state.busy && !state.uncertain && draft.inspection?.accepted == true,
                ) {
                    Text(stringResource(R.string.engine_install))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(PluginManagementAction.CloseDraft) },
                    enabled = !state.busy && !state.uncertain,
                ) {
                    Text(stringResource(R.string.engine_cancel))
                }
            },
        )
    }
    state.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { onAction(PluginManagementAction.Dismiss) },
            title = {
                Text(
                    stringResource(
                        if (confirmation == PluginConfirmation.Builds) R.string.engine_approval
                        else R.string.engine_confirm,
                    ),
                )
            },
            text = {
                Text(
                    when (confirmation) {
                        PluginConfirmation.Builds ->
                            stringResource(R.string.engine_approval_hint) +
                                "\n" +
                                state.result?.pendingBuilds.orEmpty().joinToString("\n")
                        PluginConfirmation.Install -> state.draft?.spec.orEmpty()
                        else -> confirmation.toString()
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onAction(PluginManagementAction.Confirm) }) {
                    Text(stringResource(R.string.engine_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(PluginManagementAction.Dismiss) }) {
                    Text(stringResource(R.string.engine_cancel))
                }
            },
        )
    }
}

internal val PluginCardWidth = 240.dp

/** Two bodySmall lines, reserved so every card keeps the same rhythm without its description. */
internal val PluginDescriptionHeight = 34.dp

/** One summary line (config count, failure or read-only reason) under the description. */
internal val PluginMetaHeight = 32.dp

/** The card footer's single action row; cards never grow a second row of buttons. */
internal val PluginFooterHeight = 44.dp

internal fun PluginBundle.isLocalSource(local: Boolean): Boolean =
    local &&
        source?.let {
            it.startsWith("file:") ||
                it.startsWith("/") ||
                it.startsWith("./") ||
                it.startsWith("../")
        } == true

/** Version strings stay plain M3 text; no hand-drawn rounded container. */

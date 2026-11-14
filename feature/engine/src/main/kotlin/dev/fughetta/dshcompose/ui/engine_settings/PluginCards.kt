package dev.fughetta.dshcompose.ui.engine_settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.plugins.PluginApplication
import dev.fughetta.dshcompose.domain.plugins.PluginBundle
import dev.fughetta.dshcompose.domain.plugins.PluginBundleRow
import dev.fughetta.dshcompose.domain.plugins.PluginEntry
import dev.fughetta.dshcompose.domain.plugins.PluginMarketEntry
import dev.fughetta.dshcompose.domain.sharing.SharedPlugin
import dev.fughetta.dshcompose.domain.sharing.sharedPluginFromBundle
import dev.fughetta.dshcompose.feature.engine.R
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.adaptive.IconBadge
import dev.fughetta.dshcompose.ui.adaptive.ProblemPanel
import dev.fughetta.dshcompose.ui.extendedColors

@Composable
internal fun PluginVersionLabel(version: String) {
    Text(
        version,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Filled status badge: green for enabled, neutral for disabled, never a tappable chip. */
@Composable
internal fun PluginStatusBadge(enabled: Boolean) {
    val container =
        if (enabled) MaterialTheme.extendedColors.successContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val content =
        if (enabled) MaterialTheme.extendedColors.onSuccessContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Badge(containerColor = container, contentColor = content) {
        Text(
            stringResource(
                if (enabled) R.string.engine_plugin_enabled else R.string.engine_plugin_disabled,
            ),
            maxLines = 1,
        )
    }
}

/** Progress, structured failures and the last operation result above the plugin grid. */
@Composable
internal fun PluginStatusHeader(
    state: PluginManagementState,
    onAction: (PluginManagementAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        if (state.loading || state.busy || state.marketLoading)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.cordisUnavailable)
            Text(
                stringResource(R.string.plugin_runtime_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        if (state.configUnavailable)
            Text(
                stringResource(R.string.plugin_configuration_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        state.error?.let { code ->
            ProblemPanel(
                title = stringResource(R.string.plugin_inventory_error_title),
                message = stringResource(R.string.engine_error_hint),
                code = code,
                primaryLabel = stringResource(R.string.engine_retry),
                onPrimary = { onAction(PluginManagementAction.Refresh) },
                details = state.output.takeIf { it.isNotEmpty() }?.takeLast(4096),
            )
        }
        if (state.uncertain) {
            Text(stringResource(R.string.engine_unknown))
            Button(
                onClick = { onAction(PluginManagementAction.RecoverInstall) },
                enabled = !state.busy,
            ) {
                Text(stringResource(R.string.engine_recover))
            }
        }
        if (state.busy && state.requestId != null)
            TextButton(onClick = { onAction(PluginManagementAction.CancelInstall) }) {
                Text(stringResource(R.string.engine_cancel))
            }
        state.phase?.let { Text(it) }
        if (state.error == null && state.output.isNotEmpty())
            Text(
                state.output.takeLast(4096),
                maxLines = 8,
                style = MaterialTheme.typography.bodySmall,
            )
        state.result?.let { result ->
            val failure = result.error
            if (failure == null) Text(result.application.wire)
            else
                ProblemPanel(
                    title = stringResource(R.string.engine_operation_failed),
                    message = failure.message,
                    code = failure.code,
                )
            result.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (result.application == PluginApplication.RestartRequired) {
                Text(stringResource(R.string.engine_restart_hint))
                if (state.local)
                    TextButton(
                        onClick = {
                            onAction(PluginManagementAction.Request(PluginConfirmation.Restart))
                        },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(R.string.engine_restart))
                    }
            }
        }
    }
}

/** Name search plus an enabled/disabled filter above the installed grid. */
@Composable
internal fun InstalledToolbar(
    query: String,
    onQuery: (String) -> Unit,
    filter: Int,
    onFilter: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        OutlinedTextField(
            query,
            onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(painterResource(CoreDesignsystemR.drawable.ic_search), null) },
            placeholder = { Text(stringResource(R.string.plugin_search_hint)) },
            shape = RoundedCornerShape(Spacing.Medium),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            FilterChip(
                selected = filter == InstalledFilterAll,
                onClick = { onFilter(InstalledFilterAll) },
                label = { Text(stringResource(R.string.engine_all)) },
            )
            FilterChip(
                selected = filter == InstalledFilterEnabled,
                onClick = { onFilter(InstalledFilterEnabled) },
                label = { Text(stringResource(R.string.engine_plugin_enabled)) },
            )
            FilterChip(
                selected = filter == InstalledFilterDisabled,
                onClick = { onFilter(InstalledFilterDisabled) },
                label = { Text(stringResource(R.string.engine_plugin_disabled)) },
            )
        }
    }
}

/**
 * Installed bundle card: fixed head (name, status, version), a two-line description, one summary
 * line and a single footer action row. Secondary and destructive actions live in the overflow menu;
 * configuration details open in [BundleDetailSheet].
 */
@Composable
internal fun BundleCard(
    state: PluginManagementState,
    bundle: PluginBundle,
    locale: String,
    onAction: (PluginManagementAction) -> Unit,
    onConfigure: (String) -> Unit,
    onShare: (SharedPlugin) -> Unit,
    onOpen: () -> Unit,
) {
    val title = bundle.meta?.title?.resolve(locale) ?: bundle.name
    val shareable =
        remember(bundle, locale) {
            runCatching { sharedPluginFromBundle(bundle, locale) }.getOrNull()
        }
    val configs =
        remember(bundle, state.configurableIds) {
            bundle.rows.filter { it.rowId in state.configurableIds }
        }
    val canToggle = !state.busy && bundle.readOnlyReason == null && !state.uncertain
    val toggleEnabled = {
        onAction(
            PluginManagementAction.Request(
                PluginConfirmation.BundleEnabled(bundle.name, !bundle.enabled),
            ),
        )
    }
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                IconBadge(
                    icon = CoreDesignsystemR.drawable.ic_settings,
                    modifier = Modifier.size(36.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.ExtraSmall))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                    ) {
                        PluginStatusBadge(bundle.enabled)
                        bundle.version?.let { PluginVersionLabel(it) }
                    }
                }
                BundleOverflowMenu(
                    shareable = shareable,
                    localSource = bundle.isLocalSource(state.local),
                    removable = bundle.removable && bundle.installed,
                    busy = state.busy,
                    uncertain = state.uncertain,
                    onShare = onShare,
                    onReload = {
                        onAction(
                            PluginManagementAction.Request(PluginConfirmation.Reload(bundle.name)),
                        )
                    },
                    onRemove = {
                        onAction(
                            PluginManagementAction.Request(PluginConfirmation.Remove(bundle.name)),
                        )
                    },
                )
            }
            Text(
                bundle.description?.takeIf { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = PluginDescriptionHeight),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.height(PluginMetaHeight), contentAlignment = Alignment.CenterStart) {
                when {
                    bundle.error != null ->
                        Text(
                            bundle.error?.message.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    configs.isNotEmpty() ->
                        AssistChip(
                            onClick = onOpen,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.plugin_config_count,
                                        configs.size,
                                    ),
                                )
                            },
                            modifier = Modifier.height(PluginMetaHeight),
                        )
                    bundle.readOnlyReason != null ->
                        Text(
                            bundle.readOnlyReason.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().height(PluginFooterHeight).padding(horizontal = Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = toggleEnabled, enabled = canToggle) {
                Text(
                    stringResource(
                        if (bundle.enabled) R.string.engine_disable else R.string.engine_enable,
                    ),
                )
            }
            Spacer(Modifier.weight(1f))
            if (configs.isNotEmpty())
                TextButton(onClick = onOpen, enabled = !state.busy) {
                    Text(stringResource(R.string.engine_configure))
                }
        }
    }
}

/** Overflow menu holding share, reload and the destructive remove action away from the footer. */
@Composable
internal fun BundleOverflowMenu(
    shareable: SharedPlugin?,
    localSource: Boolean,
    removable: Boolean,
    busy: Boolean,
    uncertain: Boolean,
    onShare: (SharedPlugin) -> Unit,
    onReload: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                painterResource(CoreDesignsystemR.drawable.ic_more),
                stringResource(CoreDesignsystemR.string.more_actions),
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sharing_share)) },
                enabled = shareable != null && !busy,
                onClick = {
                    open = false
                    shareable?.let(onShare)
                },
            )
            if (localSource)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.engine_reload)) },
                    enabled = !busy,
                    onClick = {
                        open = false
                        onReload()
                    },
                )
            if (removable) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.engine_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    enabled = !busy && !uncertain,
                    onClick = {
                        open = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** Runtime entry with its enable switch, sized for one grid cell. */
@Composable
internal fun EntryCard(
    state: PluginManagementState,
    entry: PluginEntry,
    locale: String,
    onAction: (PluginManagementAction) -> Unit,
    onConfigure: (String) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                IconBadge(
                    icon = CoreDesignsystemR.drawable.ic_engine,
                    modifier = Modifier.size(36.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.meta?.title?.resolve(locale) ?: entry.moduleName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.ExtraSmall))
                    PluginStatusBadge(entry.enabled)
                }
            }
            Text(
                entry.readOnlyReason ?: entry.fiberPhase.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = PluginDescriptionHeight),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.entryId in state.configurableIds) {
                TextButton(
                    onClick = { onConfigure(entry.entryId) },
                    enabled = !state.busy && !state.uncertain,
                ) {
                    Text(stringResource(R.string.engine_configure))
                }
            } else Spacer(Modifier.height(PluginMetaHeight))
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().height(PluginFooterHeight).padding(horizontal = Spacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.engine_enable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Switch(
                entry.enabled,
                onCheckedChange = {
                    onAction(
                        PluginManagementAction.Request(
                            PluginConfirmation.EntryEnabled(entry.entryId, it),
                        ),
                    )
                },
                enabled = !state.busy && !state.uncertain && entry.readOnlyReason == null,
            )
        }
    }
}

/** Detail drawer for one installed bundle: full description, its settings and its notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BundleDetailSheet(
    bundle: PluginBundle,
    configs: List<PluginBundleRow>,
    locale: String,
    shareable: SharedPlugin?,
    localSource: Boolean,
    busy: Boolean,
    uncertain: Boolean,
    onConfigure: (String) -> Unit,
    onShare: (SharedPlugin) -> Unit,
    onReload: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.ExtraLarge)
                .padding(bottom = Spacing.ExtraLarge),
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            ) {
                IconBadge(
                    icon = CoreDesignsystemR.drawable.ic_settings,
                    modifier = Modifier.size(40.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        bundle.meta?.title?.resolve(locale) ?: bundle.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        bundle.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PluginStatusBadge(bundle.enabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                bundle.version?.let { PluginVersionLabel(it) }
            }
            bundle.description
                ?.takeIf { it.isNotBlank() }
                ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            bundle.error?.let {
                Text(
                    it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            bundle.readOnlyReason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (configs.isNotEmpty()) {
                Text(
                    stringResource(R.string.plugin_config_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                configs.forEach { row ->
                    ListItem(
                        headlineContent = {
                            Text(row.meta?.title?.resolve(locale) ?: row.moduleName)
                        },
                        trailingContent = {
                            Icon(painterResource(CoreDesignsystemR.drawable.ic_chevron_right), null)
                        },
                        modifier =
                            Modifier.clickable {
                                onConfigure(row.rowId)
                                onDismiss()
                            },
                    )
                }
            }
            if (shareable == null && bundle.installed && !bundle.official)
                Text(
                    stringResource(R.string.sharing_local_plugin),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                if (localSource)
                    TextButton(
                        onClick = {
                            onReload()
                            onDismiss()
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.engine_reload))
                    }
                TextButton(
                    onClick = {
                        shareable?.let(onShare)
                        onDismiss()
                    },
                    enabled = shareable != null && !busy,
                ) {
                    Text(stringResource(R.string.sharing_share))
                }
                Spacer(Modifier.weight(1f))
                if (bundle.removable && bundle.installed)
                    TextButton(
                        onClick = {
                            onRemove()
                            onDismiss()
                        },
                        enabled = !busy && !uncertain,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text(stringResource(R.string.engine_remove))
                    }
            }
        }
    }
}

/** Market catalog card; tapping it opens the existing version/install dialog. */
@Composable
internal fun MarketPluginCard(
    entry: PluginMarketEntry,
    locale: String,
    onAction: (PluginManagementAction) -> Unit,
) {
    val title = entry.title?.resolve(locale) ?: entry.name
    OutlinedCard(
        onClick = { onAction(PluginManagementAction.SelectMarket(entry.name)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
                IconBadge(
                    icon = CoreDesignsystemR.drawable.ic_settings,
                    modifier = Modifier.size(36.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.ExtraSmall))
                    entry.versions.firstOrNull()?.let { PluginVersionLabel(it.version) }
                }
            }
            Text(
                entry.description?.resolve(locale)?.takeIf { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(min = PluginDescriptionHeight),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth()
                .height(PluginFooterHeight)
                .padding(start = Spacing.Large, end = Spacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.author?.takeIf { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { onAction(PluginManagementAction.SelectMarket(entry.name)) }) {
                Text(stringResource(R.string.engine_install))
            }
        }
    }
}

/** Search plus category/label/sort chips; stays above the market grid. */
@Composable
internal fun MarketFilterCard(
    state: PluginManagementState,
    locale: String,
    onAction: (PluginManagementAction) -> Unit,
) {
    val marketLabels = state.market?.labels?.entries?.toList().orEmpty()
    val marketSorts = state.market?.sorts?.entries?.toList().orEmpty()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            OutlinedTextField(
                state.query,
                { onAction(PluginManagementAction.Query(it)) },
                label = { Text(stringResource(R.string.engine_search)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                stringResource(R.string.engine_filter_category),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                item {
                    FilterChip(
                        selected = state.category == null,
                        onClick = { onAction(PluginManagementAction.Category(null)) },
                        label = { Text(stringResource(R.string.engine_all)) },
                    )
                }
                items(
                    state.market?.categories?.entries?.toList().orEmpty(),
                    key = { it.key },
                ) { (id, title) ->
                    FilterChip(
                        selected = state.category == id,
                        onClick = { onAction(PluginManagementAction.Category(id)) },
                        label = { Text(title.resolve(locale)) },
                    )
                }
            }
            if (marketLabels.isNotEmpty()) {
                Text(
                    stringResource(R.string.engine_filter_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    items(marketLabels, key = { it.key }) { (id, title) ->
                        FilterChip(
                            selected = state.label == id,
                            onClick = {
                                onAction(
                                    PluginManagementAction.Label(
                                        if (state.label == id) null else id,
                                    ),
                                )
                            },
                            label = { Text(title.resolve(locale)) },
                        )
                    }
                }
            }
            if (marketSorts.isNotEmpty()) {
                Text(
                    stringResource(R.string.engine_filter_sort),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    items(marketSorts, key = { it.key }) { (id, info) ->
                        FilterChip(
                            selected = state.sort == id,
                            onClick = {
                                onAction(
                                    PluginManagementAction.Sort(if (state.sort == id) null else id),
                                )
                            },
                            label = { Text(info.first.resolve(locale)) },
                        )
                    }
                }
            }
        }
    }
}

internal fun marketErrorText(code: String): Int? =
    when {
        code == "PLUGIN_MARKET_UNAVAILABLE" ||
            code == "PLUGIN_OPERATION_FAILED" ||
            code.startsWith("plugin-market/http-") -> R.string.engine_market_unavailable
        else -> null
    }

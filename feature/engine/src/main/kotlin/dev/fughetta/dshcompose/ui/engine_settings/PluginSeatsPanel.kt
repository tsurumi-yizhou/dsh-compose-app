package dev.fughetta.dshcompose.ui.engine_settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.feature.engine.R
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.EmptyStatePanel
import dev.fughetta.dshcompose.ui.adaptive.LazyAdaptiveGrid
import dev.fughetta.dshcompose.ui.adaptive.ProblemPanel
import dev.fughetta.dshcompose.ui.adaptive.WindowTier
import dev.fughetta.dshcompose.ui.adaptive.windowTier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSeatsPanel(
    state: PluginSeatsState,
    onAction: (PluginSeatsAction) -> Unit,
    sessionId: String? = null,
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(Unit) {
        onAction(PluginSeatsAction.Observe(true))
        onStopOrDispose { onAction(PluginSeatsAction.Observe(false)) }
    }
    val seats = state.seats(sessionId)
    Scaffold(
        modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreDesignsystemR.string.navigation_plugin_panels)) },
                navigationIcon = { UpButton() },
                actions = {
                    ActionIcon(
                        CoreDesignsystemR.drawable.ic_refresh,
                        stringResource(R.string.engine_refresh),
                    ) {
                        onAction(PluginSeatsAction.Refresh)
                    }
                },
            )
        },
    ) { padding ->
        val columns =
            when (windowTier()) {
                WindowTier.Expanded,
                WindowTier.Large -> 3
                WindowTier.ExtraLarge -> 4
                else -> 1
            }
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentWidths.Board)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { code ->
                    ProblemPanel(
                        title = stringResource(R.string.plugin_panels_error_title),
                        message = stringResource(R.string.plugin_panels_error_hint),
                        code = code,
                        primaryLabel = stringResource(R.string.engine_retry),
                        onPrimary = { onAction(PluginSeatsAction.Refresh) },
                    )
                }
                if (!state.occupancyCurrent) Text(stringResource(R.string.engine_occupancy_unknown))
                if (!state.loading && state.error == null && seats.isEmpty())
                    EmptyStatePanel(title = stringResource(R.string.plugin_panels_empty))
                LazyAdaptiveGrid(
                    minItemWidth = 240.dp,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    spacing = Spacing.Medium,
                    maxColumns = columns,
                ) {
                    items(seats, key = { "${it.slot}:${it.packageName}:${it.cell}" }) { spec ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ListItem(
                                content = {
                                    Text(spec.label?.resolve("en") ?: spec.packageName.orEmpty())
                                },
                                supportingContent = { Text(spec.slot) },
                            )
                        }
                    }
                }
            }
        }
    }
}

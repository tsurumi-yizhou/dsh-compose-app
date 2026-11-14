package dev.fughetta.dshcompose.ui.adaptive

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.ui.Spacing

/**
 * Window width tiers for the tablet layouts. Thresholds follow the Material 3 window size classes
 * (600/840/1200/1600 dp) so a 2960×1848 tablet at 2× density lands in [Large] and can spend the
 * extra width on side panels and card grids instead of a centered reading column.
 */
enum class WindowTier(val minWidthDp: Int) {
    Compact(0),
    Medium(600),
    Expanded(840),
    Large(1200),
    ExtraLarge(1600),
}

/** Widest tier whose breakpoint [widthDp] reaches; pure so it stays unit-testable. */
fun windowTierFor(widthDp: Int): WindowTier =
    WindowTier.entries.lastOrNull { widthDp >= it.minWidthDp } ?: WindowTier.Compact

val WindowTier.atLeastMedium: Boolean
    get() = this >= WindowTier.Medium

val WindowTier.atLeastExpanded: Boolean
    get() = this >= WindowTier.Expanded

val WindowTier.atLeastLarge: Boolean
    get() = this >= WindowTier.Large

/**
 * Current tier supplied by [ProvideWindowTier]; null means the caller did not wrap the tree, and
 * [windowTier] falls back to reading the window itself.
 */
val LocalWindowTier = compositionLocalOf<WindowTier?> { null }

/** Reads the window tier once per composition and hands it to descendants. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ProvideWindowTier(content: @Composable () -> Unit) {
    val tier = rememberWindowTier()
    CompositionLocalProvider(LocalWindowTier provides tier, content)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberWindowTier(): WindowTier {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowTier.ExtraLarge.minWidthDp) ->
            WindowTier.ExtraLarge
        sizeClass.isWidthAtLeastBreakpoint(WindowTier.Large.minWidthDp) -> WindowTier.Large
        sizeClass.isWidthAtLeastBreakpoint(WindowTier.Expanded.minWidthDp) -> WindowTier.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowTier.Medium.minWidthDp) -> WindowTier.Medium
        else -> WindowTier.Compact
    }
}

@Composable fun windowTier(): WindowTier = LocalWindowTier.current ?: rememberWindowTier()

/**
 * Content caps. Long-form text keeps a readable measure; forms and boards may grow with the window.
 * All widths are content widths, excluding the padding a screen applies around them.
 */
object ContentWidths {
    /** Prose and single-column forms: 720 dp is roughly 80–90 CJK characters per line. */
    val Reading = 760.dp

    /** Settings-style forms; wide enough for two label/control columns. */
    val Form = 1040.dp

    /** Card boards, dashboards and grids. */
    val Board = 1360.dp

    /** Dense chrome such as host/engine tables. */
    val Wide = 1600.dp
}

/**
 * Centers content at [maxContentWidth] instead of letting it stretch edge to edge. The caller keeps
 * its own scroll container; this only handles horizontal measure.
 */
@Composable
fun CenteredContent(
    modifier: Modifier = Modifier,
    maxContentWidth: Dp = ContentWidths.Reading,
    horizontalPadding: Dp = Spacing.Large,
    verticalPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier.widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            content = content,
        )
    }
}

/** Number of equal columns that fit when each item needs [minItemWidth] plus [spacing]. */
fun BoxWithConstraintsScope.adaptiveColumnCount(
    minItemWidth: Dp,
    spacing: Dp = Spacing.Large,
    maxColumns: Int = 6,
): Int {
    val usable = maxWidth + spacing
    val fit = (usable / (minItemWidth + spacing)).toInt()
    return fit.coerceIn(1, maxColumns)
}

/**
 * Wrapping card grid for bounded lists (settings groups, presets, dashboards). Safe inside a
 * scrolling [Column] because it measures with the parent's constraints instead of nesting a lazy
 * list. Use [LazyAdaptiveGrid] for long or paged collections.
 */
@Composable
fun <T> AdaptiveCardGrid(
    items: List<T>,
    minItemWidth: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = Spacing.Large,
    maxColumns: Int = 4,
    columns: Int? = null,
    itemContent: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val columns = columns ?: adaptiveColumnCount(minItemWidth, spacing, maxColumns)
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            items.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { itemContent(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Lazy variant for long collections: fixed column count derived from the available width. */
@Composable
fun LazyAdaptiveGrid(
    minItemWidth: Dp,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = Spacing.Large,
    maxColumns: Int = 6,
    content: LazyGridScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val columns = adaptiveColumnCount(minItemWidth, spacing, maxColumns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * Desktop-style two-pane board: a secondary rail on the left, section content on the right. Callers
 * decide when the rail earns its width (see [windowTier]) and fall back to the single-column phone
 * layout on narrow windows.
 */
@Composable
fun TwoPaneBoard(
    modifier: Modifier = Modifier,
    railWidth: Dp = 244.dp,
    rail: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    PermanentNavigationDrawer(
        modifier = modifier.fillMaxSize(),
        drawerContent = {
            PermanentDrawerSheet(Modifier.width(railWidth)) {
                Column(
                    Modifier.padding(vertical = Spacing.Small, horizontal = Spacing.Small),
                    content = rail,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize(), content = content)
    }
}

@Immutable
data class RailItem(
    val id: String,
    val label: String,
    @DrawableRes val icon: Int? = null,
)

/** Vertical secondary navigation used by settings and the phone-controls pages. */
@Composable
fun SectionRailNav(
    items: List<RailItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            NavigationDrawerItem(
                label = {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                selected = item.id == selectedId,
                onClick = { onSelect(item.id) },
                icon =
                    item.icon?.let { icon ->
                        { Icon(painterResource(icon), contentDescription = null) }
                    },
                modifier = Modifier.padding(horizontal = Spacing.Small),
            )
        }
    }
}

/** Section heading for control-panel groups. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Native settings section: a small header followed by rows on the page background. The rows keep
 * their own [ListItem] surface, so nothing paints a tonal bar behind the header; group separation
 * comes from spacing and the row dividers instead of a card fill.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = Spacing.Large,
                        end = Spacing.Large,
                        top = Spacing.Large,
                        bottom = Spacing.ExtraSmall,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(title, Modifier.weight(1f), supporting)
            trailing?.invoke()
        }
        Column(content = content)
    }
}

/**
 * Native-looking settings row: flat list item with an optional leading icon, supporting text and a
 * trailing current value plus chevron. Mirrors how the system Settings app renders rows, so
 * settings pages read as part of the platform instead of a custom card board.
 */

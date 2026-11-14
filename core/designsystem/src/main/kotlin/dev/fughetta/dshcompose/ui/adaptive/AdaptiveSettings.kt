package dev.fughetta.dshcompose.ui.adaptive

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.extendedColors

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes leadingIcon: Int? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier =
            if (onClick != null) modifier.clickable(enabled = enabled, onClick = onClick)
            else modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent =
            leadingContent
                ?: leadingIcon?.let { icon ->
                    {
                        Icon(
                            painterResource(icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { text -> { Text(text) } },
        trailingContent =
            if (value != null || trailingContent != null || chevron) {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
                    ) {
                        if (value != null) {
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        trailingContent?.invoke()
                        if (chevron) {
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            } else null,
    )
}

/** Hairline between rows of a [SettingsGroup], indented past the leading icon like the system. */
@Composable
fun SettingsRowDivider(startIndent: Dp = 72.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent, end = Spacing.Large),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

/**
 * Flat settings group: a small section label followed by rows on the page background, no card and
 * no tonal fill. This is the shape the system Settings app uses on tablets.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                modifier =
                    Modifier.padding(
                        start = Spacing.Large,
                        end = Spacing.Large,
                        top = Spacing.Large,
                        bottom = Spacing.ExtraSmall,
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(content = content)
    }
}

/**
 * Rounded icon badge used by modern settings rows: a 40dp tinted circle with a 20dp glyph. Keeps
 * the icon column consistent even when a page has no matching asset for every row.
 */
@Composable
fun IconBadge(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Modern grouped settings card: a rounded tonal container holding plain [ListItem] rows. Row
 * containers must stay transparent (see [SettingsRow]) so the whole group reads as one surface
 * instead of a grey header bar with white strips underneath.
 */
@Composable
fun SettingsCardGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    supporting: String? = null,
    @DrawableRes headerIcon: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Row(
                modifier = Modifier.padding(start = Spacing.Medium, bottom = Spacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                if (headerIcon != null) {
                    Icon(
                        painterResource(headerIcon),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(Modifier.padding(vertical = Spacing.Small), content = content)
        }
    }
}

/** Large page title paired with a rounded search field, as in current system settings. */
@Composable
fun SettingsPageHeader(
    title: String,
    searchPlaceholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Medium)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ListItem(
            headlineContent = { Text(searchPlaceholder) },
            leadingContent = { Icon(painterResource(R.drawable.ic_search), null) },
            colors =
                ListItemDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            modifier =
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).clickable(
                    enabled = onSearch != null,
                    role = Role.Button,
                ) {
                    onSearch?.invoke()
                },
        )
    }
}

/** Metric tone: neutral data, healthy, needs attention, or broken. */
enum class MetricTone {
    Neutral,
    Positive,
    Warning,
    Critical,
}

@Immutable
data class MetricTile(
    val label: String,
    val value: String,
    val unit: String? = null,
    val supporting: String? = null,
    val tone: MetricTone = MetricTone.Neutral,
    /** Optional 0..1 bar under the value, e.g. disk usage. */
    val progress: Float? = null,
)

@Composable
private fun metricColor(tone: MetricTone): Color =
    when (tone) {
        MetricTone.Neutral -> MaterialTheme.colorScheme.onSurface
        MetricTone.Positive -> MaterialTheme.extendedColors.success
        MetricTone.Warning -> MaterialTheme.extendedColors.warning
        MetricTone.Critical -> MaterialTheme.colorScheme.error
    }

@Composable
fun MetricCard(tile: MetricTile, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().height(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.Large), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                tile.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    tile.value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = metricColor(tile.tone),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tile.unit != null) {
                    Text(
                        " ${tile.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            tile.supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tile.progress?.let { progress ->
                Spacer(Modifier.height(Spacing.Small))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

/** Multi-metric dashboard grid; tiles keep a minimum width and wrap into rows. */
@Composable
fun MetricsBoard(
    tiles: List<MetricTile>,
    modifier: Modifier = Modifier,
    minTileWidth: Dp = 180.dp,
) {
    BoxWithConstraints(modifier) {
        var columns = adaptiveColumnCount(minTileWidth, Spacing.Medium, 4)
        // A single leftover tile reads as a stray card; prefer a fuller last row.
        while (columns > 2 && tiles.size % columns == 1) columns--
        AdaptiveCardGrid(
            items = tiles,
            minItemWidth = minTileWidth,
            spacing = Spacing.Medium,
            columns = columns,
        ) { tile ->
            MetricCard(tile)
        }
    }
}

/**
 * Structured empty state: icon, headline, explanation and up to two actions, plus an [extra] slot
 * for template chips or illustrated placeholders. Use instead of a lone line of text on wide
 * screens.
 */
@Composable
fun EmptyStatePanel(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    @DrawableRes icon: Int? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(Spacing.ExtraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            if (icon != null) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(Spacing.ExtraSmall))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (primaryLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(Spacing.Small))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    if (primaryLabel != null && onPrimary != null) {
                        Button(onClick = onPrimary) { Text(primaryLabel) }
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                    }
                }
            }
            extra?.let {
                Spacer(Modifier.height(Spacing.Medium))
                it()
            }
        }
    }
}

enum class ProblemSeverity {
    Warning,
    Error,
}

/**
 * Structured failure card for transport/engine errors ("http-404", unreadable state). Keeps the
 * error code visible and offers recovery actions instead of a bare red line.
 */
@Composable
fun ProblemPanel(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    code: String? = null,
    severity: ProblemSeverity = ProblemSeverity.Error,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    details: String? = null,
) {
    val accent =
        when (severity) {
            ProblemSeverity.Warning -> MaterialTheme.extendedColors.warning
            ProblemSeverity.Error -> MaterialTheme.colorScheme.error
        }
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            code?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (details != null) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                ) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = 132.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.Medium),
                    )
                }
            }
            if (primaryLabel != null || secondaryLabel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    if (primaryLabel != null && onPrimary != null) {
                        Button(onClick = onPrimary) { Text(primaryLabel) }
                    }
                    if (secondaryLabel != null && onSecondary != null) {
                        TextButton(onClick = onSecondary) { Text(secondaryLabel) }
                    }
                }
            }
        }
    }
}

/** Save lifecycle for forms that write the engine on an explicit action. */
enum class SaveStatus {
    /** Nothing to write. */
    Idle,
    /** Local edits pending. */
    Dirty,
    Saving,
    Saved,
    Failed,
}

@Immutable
data class SaveLabels(
    val dirty: String,
    val saving: String,
    val saved: String,
    val failed: String,
    val save: String,
    val retry: String = save,
)

/**
 * Persistent footer for forms: bottom-anchored status plus the save action, so saving never needs a
 * trip to the top-right corner. Pair with [saveShortcut] on the screen root.
 */
@Composable
fun SaveStatusBar(
    status: SaveStatus,
    labels: SaveLabels,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent =
        when (status) {
            SaveStatus.Dirty -> MaterialTheme.colorScheme.primary
            SaveStatus.Saving -> MaterialTheme.colorScheme.primary
            SaveStatus.Saved -> MaterialTheme.extendedColors.success
            SaveStatus.Failed -> MaterialTheme.colorScheme.error
            SaveStatus.Idle -> MaterialTheme.colorScheme.outline
        }
    val label =
        when (status) {
            SaveStatus.Idle -> ""
            SaveStatus.Dirty -> labels.dirty
            SaveStatus.Saving -> labels.saving
            SaveStatus.Saved -> labels.saved
            SaveStatus.Failed -> labels.failed
        }
    BottomAppBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Spacer(Modifier.width(Spacing.Small))
        Badge(containerColor = accent)
        Spacer(Modifier.width(Spacing.Medium))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        when (status) {
            SaveStatus.Saving -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            SaveStatus.Dirty -> Button(onClick = onSave) { Text(labels.save) }
            SaveStatus.Failed -> Button(onClick = onSave) { Text(labels.retry) }
            else -> Unit
        }
    }
}

/**
 * Ctrl/⌘+S handler for the screen that owns a form. Attach to a root that contains the focused
 * editor; key events bubble from the focused child up through preview handlers.
 */
fun Modifier.saveShortcut(onSave: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val modifier = event.isCtrlPressed || event.isMetaPressed
    if (
        modifier && !event.isAltPressed && event.type == KeyEventType.KeyDown && event.key == Key.S
    ) {
        onSave()
        true
    } else {
        false
    }
}

/** Six-dot drag handle for reorderable rows; the touch target is wider than the dots. */
/** Smaller variant of [CenteredContent] for a form that benefits from a two-column row. */

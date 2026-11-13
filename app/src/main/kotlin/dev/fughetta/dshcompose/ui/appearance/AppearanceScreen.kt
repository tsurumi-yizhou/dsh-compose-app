package dev.fughetta.dshcompose.ui.appearance

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    state: AppearanceState,
    onAction: (AppearanceAction) -> Unit,
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_appearance)) },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        val wide = windowTier().atLeastExpanded
        // Keep the theme choices and their preview together at every window width.
        CenteredContent(
            modifier =
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            maxContentWidth = if (wide) ContentWidths.Form else ReadableWidth,
            verticalPadding = Spacing.Small,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraLarge)) {
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { code ->
                    Text(
                        stringResource(appearanceError(code)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.notice?.let { code ->
                    Text(
                        stringResource(
                            if (code == "APPEARANCE_SHORTCUT_ADDED") R.string.app_shortcut_added
                            else if (code == "APPEARANCE_SHORTCUT_REQUESTED")
                                R.string.app_shortcut_requested
                            else R.string.app_saved,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                AppearanceChoices(state, onAction, mode, onMode)
            }
        }
    }
}

/** Theme selection and accent editing share one layout. */
@Composable
private fun AppearanceChoices(
    state: AppearanceState,
    onAction: (AppearanceAction) -> Unit,
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = state.preferences
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.ExtraLarge)) {
        Section(R.string.app_theme) {
            Choices(ThemeMode.entries, mode, { onMode(it) }) { stringResource(themeLabel(it)) }
        }
        Section(R.string.app_color) {
            AppearancePreview(mode)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.selectableGroup(),
            ) {
                appearancePresets.forEach { (id, argb) ->
                    val selected = prefs.source == AccentSource.Preset && prefs.preset == id
                    Column(
                        Modifier.width(72.dp)
                            .selectable(selected, enabled = !state.busy, role = Role.RadioButton) {
                                onAction(AppearanceAction.Preset(id))
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(44.dp)
                                .background(Color(argb), CircleShape)
                                .then(
                                    if (selected)
                                        Modifier.border(
                                            3.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        )
                                    else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected)
                                Text(
                                    "✓",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                        }
                        Text(
                            stringResource(presetLabel(id)),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (state.wallpaperAccent != null)
                FilterChip(
                    selected = prefs.source == AccentSource.Wallpaper,
                    onClick = { onAction(AppearanceAction.Source(AccentSource.Wallpaper)) },
                    enabled = !state.busy,
                    label = { Text(stringResource(R.string.app_wallpaper_color)) },
                )
            var editing by remember { mutableStateOf(false) }
            FilterChip(
                selected = prefs.source == AccentSource.Custom,
                onClick = { editing = true },
                enabled = !state.busy,
                label = { Text(stringResource(R.string.app_custom_color)) },
                leadingIcon = {
                    Box(Modifier.size(20.dp).background(Color(prefs.customArgb), CircleShape))
                },
            )
            if (editing) {
                var hex by remember { mutableStateOf("%06X".format(prefs.customArgb and 0xFFFFFF)) }
                val parsed =
                    hex.removePrefix("#")
                        .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
                        ?.toLongOrNull(16)
                AlertDialog(
                    onDismissRequest = { editing = false },
                    title = { Text(stringResource(R.string.app_custom_color)) },
                    text = {
                        OutlinedTextField(
                            hex,
                            { hex = it.take(7) },
                            label = { Text(stringResource(R.string.app_color_hex)) },
                            singleLine = true,
                            isError = parsed == null,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                parsed?.let {
                                    onAction(AppearanceAction.CustomColor(it.toInt()))
                                    editing = false
                                }
                            },
                            enabled = parsed != null && !state.busy,
                        ) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editing = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }
}

/** A small conversation sample uses the active theme colors. */
@Composable
private fun AppearancePreview(mode: ThemeMode, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        color = colors.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.app_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Surface(
                Modifier.align(Alignment.End),
                color = colors.primaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    stringResource(R.string.app_preview_sample),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(24.dp).background(colors.primary, CircleShape))
                Text(stringResource(themeLabel(mode)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Use vertical choices when a segmented row has insufficient space. */
@Composable
private fun <T> Choices(
    values: Iterable<T>,
    selected: T,
    onClick: (T) -> Unit,
    label: @Composable (T) -> String,
) {
    val items = values.toList()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < NarrowControlWidth * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            Column(Modifier.selectableGroup()) {
                items.forEach { item ->
                    RadioListItem(label(item), selected == item, { onClick(item) })
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected == item,
                        { onClick(item) },
                        SegmentedButtonDefaults.itemShape(index, items.size),
                    ) {
                        Text(label(item), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.System -> R.string.app_system
        ThemeMode.Light -> R.string.app_light
        ThemeMode.Dark -> R.string.app_dark
    }

private fun presetLabel(id: String): Int =
    when (id) {
        "mist" -> R.string.app_mist
        "night" -> R.string.app_night
        "frost" -> R.string.app_frost
        "ink" -> R.string.app_ink
        "daiqing" -> R.string.app_daiqing
        "dusk" -> R.string.app_dusk
        "turquoise" -> R.string.app_turquoise
        "amber" -> R.string.app_amber
        else -> R.string.app_graphite
    }

private fun appearanceError(code: String): Int =
    when (code) {
        "APPEARANCE_MEDIA_TOO_LARGE" -> R.string.app_media_too_large
        "APPEARANCE_MEDIA_UNSUPPORTED" -> R.string.app_media_unsupported
        "APPEARANCE_SAVE_FAILED" -> R.string.app_save_failed
        "APPEARANCE_ICON_FAILED" -> R.string.app_icon_failed
        "APPEARANCE_SHORTCUT_REFUSED" -> R.string.app_shortcut_refused
        else -> R.string.app_media_unreadable
    }

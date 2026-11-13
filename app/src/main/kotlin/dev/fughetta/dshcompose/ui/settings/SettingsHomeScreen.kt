package dev.fughetta.dshcompose.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.AppAction
import dev.fughetta.dshcompose.ui.AppState
import dev.fughetta.dshcompose.ui.ErrorCard
import dev.fughetta.dshcompose.ui.FeatureContribution
import dev.fughetta.dshcompose.ui.FeaturePage
import dev.fughetta.dshcompose.ui.FeaturePlacement
import dev.fughetta.dshcompose.ui.SettingsSection
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.adaptive.IconBadge
import dev.fughetta.dshcompose.ui.adaptive.SettingsCardGroup
import dev.fughetta.dshcompose.ui.adaptive.SettingsRow
import dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import dev.fughetta.dshcompose.ui.models.ModelDefaultsUiState
import kotlinx.coroutines.flow.StateFlow

/** Rail id of the "everything" pane above the [SettingsSection] groups. */
private const val OverviewRailId = "overview"

/** Gap between cards and the indent of the hairline between two rows with an [IconBadge]. */
private val CardSpacing = 16.dp
private val RowDividerIndent = 72.dp

/**
 * 设置首页: a permanent M3 drawer of groups on the left and grouped M3 cards on the right. The phone
 * keeps a single scrolling column of the same cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: AppState,
    defaults: StateFlow<ModelDefaultsUiState>,
    onAction: (AppAction) -> Unit,
    onPage: (FeaturePage) -> Unit,
    onLanguage: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    ActionIcon(
                        R.drawable.ic_search,
                        stringResource(R.string.settings_search_title),
                    ) {
                        onPage(FeaturePage.Search)
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        SettingsPhone(state, defaults, onAction, onPage, onLanguage, modifier)
    }
}

/** Phone layout: one column of cards, no rail; the top bar owns the search entry. */
@Composable
private fun SettingsPhone(
    state: AppState,
    defaults: StateFlow<ModelDefaultsUiState>,
    onAction: (AppAction) -> Unit,
    onPage: (FeaturePage) -> Unit,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.contributions.at(FeaturePlacement.Settings)
    val hub = entries.filter { it.section == null }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.Medium, vertical = Spacing.Small),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        state.error?.let { item("error") { ErrorCard(it) } }
        // Hub links without a group lead; every other entry sits under its group.
        if (hub.isNotEmpty()) {
            item("hub") {
                SettingsCardGroup {
                    hub.forEachIndexed { index, entry ->
                        SettingsEntry(entry, onAction, onPage)
                        if (index != hub.lastIndex) SettingsRowDivider(RowDividerIndent)
                    }
                }
            }
        }
        for (section in visibleSections(entries)) {
            item("group:${section.name}") {
                SettingsSectionCard(
                    section,
                    entries,
                    state,
                    defaults,
                    onAction,
                    onPage,
                    onLanguage,
                )
            }
        }
    }
}

/** Groups that have a header to show; General always shows (it owns the language rows). */
private fun visibleSections(entries: List<FeatureContribution>): List<SettingsSection> =
    SettingsSection.entries.filter { section ->
        section == SettingsSection.General || entries.any { it.section == section }
    }

/** One group card: its rows, the General controls, and the General controls. */
@Composable
private fun SettingsSectionCard(
    section: SettingsSection,
    entries: List<FeatureContribution>,
    state: AppState,
    defaults: StateFlow<ModelDefaultsUiState>,
    onAction: (AppAction) -> Unit,
    onPage: (FeaturePage) -> Unit,
    onLanguage: () -> Unit,
) {
    val rows = entries.filter { it.section == section }
    val modelDefaults by defaults.collectAsStateWithLifecycle()
    val selection = modelDefaults.snapshot?.selection
    val generalExtras = if (section == SettingsSection.General) 1 else 0
    val total = generalExtras + rows.size
    var index = 0
    SettingsCardGroup(
        title = stringResource(section.title),
        headerIcon = sectionIcon(section),
    ) {
        if (section == SettingsSection.General) {
            GroupRow(index++ == total - 1) { LanguageRow(state.language, onLanguage) }
        }
        rows.forEach { entry ->
            GroupRow(index++ == total - 1) {
                SettingsEntry(
                    entry,
                    onAction,
                    onPage,
                    supporting =
                        when (entry.id) {
                            "settings.local_engine" ->
                                dev.fughetta.dshcompose.ui.runtime.phaseName(
                                    state.engineControls.phase,
                                )
                            "settings.providers" ->
                                selection?.let { "${it.provider} · ${it.model}" }
                            else -> null
                        },
                )
            }
        }
    }
}

/** A row plus the hairline that follows it, unless it is the last row of the card. */
@Composable
private fun GroupRow(last: Boolean, content: @Composable () -> Unit) {
    content()
    if (!last) SettingsRowDivider(RowDividerIndent)
}

@Composable
private fun SettingsEntry(
    entry: FeatureContribution,
    onAction: (AppAction) -> Unit,
    onPage: (FeaturePage) -> Unit,
    supporting: String? = null,
) {
    SettingsRow(
        title = stringResource(entry.label),
        supporting = supporting ?: entry.supportingLabel?.let { stringResource(it) },
        leadingContent = { IconBadge(icon = entryIcon(entry)) },
        chevron = true,
        onClick = { entry.target(null)?.open(onAction, onPage) ?: Unit },
    )
}

/**
 * A settings row whose value opens a single-choice picker. Tablets get a centered dialog; phones
 * get a dropdown whose anchor is the finger's touch point on the row, so it never appears far from
 * where the user tapped.
 */
@Composable
private fun <T> SettingsChoiceRow(
    title: String,
    current: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    @DrawableRes icon: Int,
) {
    val wide = windowTier().atLeastExpanded
    var open by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) touch = change.position
                    }
                }
            },
    ) {
        SettingsRow(
            title = title,
            value = current,
            leadingContent = { IconBadge(icon = icon) },
            chevron = true,
            onClick = { open = true },
        )
        if (!wide) {
            // DropdownMenu positions itself around its anchor's bounds and adds `offset` on top of
            // them, so offsetting by the touch point would push the menu a row away from the
            // finger. Anchor a zero-size box at the touch point instead, and fall back to the row's
            // center when the click came from a keyboard or an accessibility service.
            val anchor = touch ?: Offset(size.width / 2f, size.height / 2f)
            Box(Modifier.align(Alignment.TopStart).absoluteOffset { anchor.round() }.size(0.dp)) {
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    modifier = Modifier.widthIn(min = 200.dp),
                ) {
                    options.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                open = false
                                onSelect(value)
                            },
                            leadingIcon = {
                                RadioButton(selected = value == selected, onClick = null)
                            },
                        )
                    }
                }
            }
        }
    }
    if (wide && open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    options.forEach { (value, label) ->
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        open = false
                                        onSelect(value)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Spacer(Modifier.width(Spacing.Small))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Opens language preferences as a normal navigation destination. */
@Composable
private fun LanguageRow(language: String?, onOpen: () -> Unit) {
    SettingsRow(
        title = stringResource(R.string.app_language),
        value =
            when (language) {
                "zh" -> "中文"
                "en" -> "English"
                else -> stringResource(R.string.follow_system)
            },
        leadingContent = { IconBadge(icon = R.drawable.ic_s_language) },
        chevron = true,
        onClick = onOpen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageScreen(language: String?, onChange: (String?) -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_language)) },
                navigationIcon = { dev.fughetta.dshcompose.ui.UpButton() },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.Medium).selectableGroup()) {
            listOf(null to stringResource(R.string.follow_system), "zh" to "中文", "en" to "English")
                .forEach { (value, label) ->
                    dev.fughetta.dshcompose.ui.RadioListItem(
                        label,
                        language == value,
                        { onChange(value) },
                    )
                }
        }
    }
}

private fun sectionIcon(section: SettingsSection): Int =
    when (section) {
        SettingsSection.General -> R.drawable.ic_s_tune
        SettingsSection.Ai -> R.drawable.ic_s_spark
        SettingsSection.Capabilities -> R.drawable.ic_s_widgets
        SettingsSection.Display -> R.drawable.ic_s_palette
        SettingsSection.Advanced -> R.drawable.ic_s_tune
        SettingsSection.Hosts -> R.drawable.ic_s_monitor
        SettingsSection.Tools -> R.drawable.ic_s_tune
        SettingsSection.Help -> R.drawable.ic_s_help
    }

/** Each built-in settings destination has its own glyph. */
@DrawableRes
private fun entryIcon(entry: FeatureContribution): Int =
    when (entry.id) {
        "settings.navigation_notifications" -> R.drawable.ic_s_bell
        "settings.providers" -> R.drawable.ic_s_cloud
        "settings.permissions_title" -> R.drawable.ic_s_lock
        "settings.personalization_title" -> R.drawable.ic_s_person
        "settings.agent_presets" -> R.drawable.ic_s_robot
        "settings.navigation_phone_controls" -> R.drawable.ic_s_phone
        "settings.navigation_plugins" -> R.drawable.ic_s_extension
        "settings.navigation_plugin_panels" -> R.drawable.ic_s_panels
        "settings.navigation_native_plugins" -> R.drawable.ic_s_widgets
        "settings.navigation_media_settings" -> R.drawable.ic_s_image
        "settings.navigation_media_works" -> R.drawable.ic_s_gallery
        "settings.appearance" -> R.drawable.ic_s_palette
        "settings.quick_panel_title" -> R.drawable.ic_s_shortcut
        "settings.local_engine" -> R.drawable.ic_s_chip
        "settings.navigation_compose_services" -> R.drawable.ic_s_server
        "settings.navigation_engine_configuration" -> R.drawable.ic_s_configuration
        "settings.navigation_terminal" -> R.drawable.ic_s_terminal
        "settings.navigation_schedules" -> R.drawable.ic_s_calendar
        "settings.ask_ai_help" -> R.drawable.ic_s_help
        "settings.about" -> R.drawable.ic_s_info
        else -> entry.section?.let(::sectionIcon) ?: R.drawable.ic_s_tune
    }

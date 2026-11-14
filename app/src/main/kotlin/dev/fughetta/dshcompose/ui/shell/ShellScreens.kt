package dev.fughetta.dshcompose.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.ui.ReadableWidth
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.CenteredContent
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.EmptyStatePanel
import dev.fughetta.dshcompose.ui.adaptive.SettingsRowDivider
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

@Composable
private fun FeatureRow(entry: FeatureEntry, supporting: String? = null, onOpen: (String) -> Unit) {
    ListItem(
        content = { Text(stringResource(entry.title)) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Icon(
                painterResource(CoreDesignsystemR.drawable.ic_chevron_right),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable { onOpen(entry.id) },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Settings search: one query against every page of [FeatureCatalog], in Chinese and English at
 * once. With no match, 「问 AI」 takes the query to a new conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSearchScreen(
    recent: List<String>,
    onOpen: (String) -> Unit,
    onAction: (ShellPrefsAction) -> Unit,
    onAskAi: ((String) -> Unit)?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val texts = rememberFeatureTexts()
    val results = remember(query, texts) { rankFeatures(query, texts) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val open = { id: String ->
        onAction(ShellPrefsAction.RememberSearch(query))
        onOpen(id)
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextField(
                            query,
                            { query = it },
                            Modifier.widthIn(max = ReadableWidth)
                                .fillMaxWidth()
                                .focusRequester(focus),
                            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                            singleLine = true,
                            trailingIcon = {
                                if (query.isNotEmpty())
                                    IconButton({ query = "" }) {
                                        Icon(
                                            painterResource(CoreDesignsystemR.drawable.ic_close),
                                            stringResource(R.string.settings_search_clear),
                                        )
                                    }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = { results.firstOrNull()?.let { open(it.id) } },
                                ),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                        )
                    }
                },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        CenteredContent(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            maxContentWidth = ContentWidths.Form,
            verticalPadding = Spacing.Small,
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                when {
                    query.isBlank() && recent.isNotEmpty() -> {
                        item("recent") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    SectionHeader(stringResource(R.string.settings_search_recent))
                                }
                                TextButton({ onAction(ShellPrefsAction.ClearSearches) }) {
                                    Text(stringResource(R.string.settings_search_clear_recent))
                                }
                            }
                        }
                        items(recent, key = { "recent:$it" }) { text ->
                            ListItem(
                                content = { Text(text) },
                                leadingContent = {
                                    Icon(
                                        painterResource(CoreDesignsystemR.drawable.ic_search),
                                        null,
                                    )
                                },
                                modifier = Modifier.clickable { query = text },
                            )
                        }
                    }
                    query.isBlank() -> Unit
                    results.isEmpty() -> {
                        item("empty") {
                            EmptyStatePanel(
                                title =
                                    stringResource(
                                        R.string.settings_search_empty,
                                        query.trim(),
                                    ),
                                icon = CoreDesignsystemR.drawable.ic_search,
                            )
                        }
                        onAskAi?.let { ask ->
                            item("ask") {
                                ListItem(
                                    content = {
                                        Text(
                                            stringResource(
                                                R.string.settings_search_ask,
                                                query.trim(),
                                            ),
                                        )
                                    },
                                    leadingContent = {
                                        Icon(painterResource(R.drawable.ic_chat), null)
                                    },
                                    modifier =
                                        Modifier.clickable {
                                            onAction(ShellPrefsAction.RememberSearch(query))
                                            ask(query.trim())
                                        },
                                )
                            }
                        }
                    }
                    else ->
                        items(results, key = { it.id }) { entry ->
                            FeatureRow(entry, stringResource(entry.section.title), open)
                        }
                }
            }
        }
    }
}

/**
 * 快捷入口 settings: the swipe, then which pages the panel shows and in what order. Each list is a
 * tonal group of full-width rows so a toggle reads as one tappable card row instead of a checkbox
 * floating on the page; the shown list reorders by dragging its handle, and move up / move down
 * stay reachable as accessibility actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPanelSettingsScreen(state: ShellPrefsState, onAction: (ShellPrefsAction) -> Unit) {
    val hidden = LocalFeatureCatalog.current.filter { it.id !in state.quickPanelItems }
    val set = { items: List<String> -> onAction(ShellPrefsAction.QuickPanelItems(items)) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreDesignsystemR.string.quick_panel_title)) },
                navigationIcon = { UpButton() },
            )
        },
    ) { padding ->
        val wide = windowTier().atLeastExpanded
        CenteredContent(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            maxContentWidth = if (wide) ContentWidths.Form else ReadableWidth,
            verticalPadding = Spacing.Small,
        ) {
            if (wide && hidden.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Large),
                    ) {
                        QuickPanelGestureGroup(state, onAction)
                        QuickPanelShownGroup(state.quickPanelItems, set)
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Large),
                    ) {
                        QuickPanelMoreGroup(hidden) { set(state.quickPanelItems + it) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                    QuickPanelGestureGroup(state, onAction)
                    QuickPanelShownGroup(state.quickPanelItems, set)
                    if (hidden.isNotEmpty())
                        QuickPanelMoreGroup(hidden) { set(state.quickPanelItems + it) }
                }
            }
        }
    }
}

/** Card-shaped settings group: a small header, then rows on a quiet tonal surface. */
@Composable
private fun QuickPanelGroup(
    title: String,
    supporting: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(vertical = Spacing.Small)) {
            Column(Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (supporting != null)
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            content()
        }
    }
}

@Composable
private fun QuickPanelGestureGroup(state: ShellPrefsState, onAction: (ShellPrefsAction) -> Unit) {
    QuickPanelGroup(
        stringResource(R.string.quick_panel_gesture),
        stringResource(R.string.quick_panel_gesture_hint),
    ) {
        QuickPanelSwitchRow(
            stringResource(R.string.quick_panel_swipe),
            state.quickPanelSwipe,
        ) {
            onAction(ShellPrefsAction.QuickPanelSwipe(it))
        }
    }
}

@Composable
private fun QuickPanelSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier =
            Modifier.toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        content = { Text(title) },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * The ordered panel contents. Dragging a handle moves its row, the others slide out of the way, and
 * the new order is committed on release.
 */
@Composable
private fun QuickPanelShownGroup(items: List<String>, set: (List<String>) -> Unit) {
    val catalog = LocalFeatureCatalog.current
    val entries = items.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    val draggingIndex = if (dragging == null) -1 else entries.indexOfFirst { it.id == dragging }
    val targetIndex =
        if (draggingIndex < 0 || rowHeight <= 0f) draggingIndex
        else (draggingIndex + (dragDy / rowHeight).roundToInt()).coerceIn(0, entries.lastIndex)

    fun offsetOf(index: Int): Float =
        when {
            draggingIndex < 0 || targetIndex < 0 -> 0f
            index == draggingIndex -> dragDy
            draggingIndex < targetIndex && index in (draggingIndex + 1)..targetIndex -> -rowHeight
            draggingIndex > targetIndex && index in targetIndex until draggingIndex -> rowHeight
            else -> 0f
        }

    QuickPanelGroup(
        stringResource(R.string.quick_panel_shown),
        stringResource(R.string.quick_panel_shown_hint),
    ) {
        if (entries.isEmpty())
            Text(
                stringResource(R.string.quick_panel_empty),
                Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        entries.forEachIndexed { index, entry ->
            QuickPanelShownRow(
                entry = entry,
                modifier =
                    Modifier.onGloballyPositioned {
                            if (rowHeight <= 0f) rowHeight = it.size.height.toFloat()
                        }
                        .offset { IntOffset(0, offsetOf(index).roundToInt()) }
                        .zIndex(if (index == draggingIndex) 1f else 0f),
                onToggle = { set(items - entry.id) },
                canMoveUp = index > 0,
                canMoveDown = index < entries.lastIndex,
                onMove = { delta -> set(moveVisible(items, index, index + delta, catalog)) },
                onDragStart = {
                    dragging = entry.id
                    dragDy = 0f
                },
                onDrag = { dragDy += it },
                onDragEnd = {
                    val from =
                        if (dragging == null) -1 else entries.indexOfFirst { it.id == dragging }
                    val to =
                        if (from < 0 || rowHeight <= 0f) from
                        else
                            (from + (dragDy / rowHeight).roundToInt()).coerceIn(
                                0,
                                entries.lastIndex,
                            )
                    if (from >= 0 && to != from) set(moveVisible(items, from, to, catalog))
                    dragging = null
                    dragDy = 0f
                },
                onDragCancel = {
                    dragging = null
                    dragDy = 0f
                },
            )
            if (dragging == null && index < entries.lastIndex)
                SettingsRowDivider(startIndent = Spacing.Large)
        }
    }
}

@Composable
private fun QuickPanelShownRow(
    entry: FeatureEntry,
    modifier: Modifier,
    onToggle: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val moveUp = stringResource(R.string.quick_panel_move_up)
    val moveDown = stringResource(R.string.quick_panel_move_down)
    val reorder = stringResource(R.string.quick_panel_reorder)
    // The gesture detector outlives a recomposition, so it reads the latest callbacks instead of
    // the ones captured when the pointer went down.
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    ListItem(
        modifier =
            modifier.toggleable(value = true, role = Role.Switch, onValueChange = { onToggle() }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(48.dp)
                    .semantics {
                        contentDescription = reorder
                        customActions =
                            listOfNotNull(
                                if (canMoveUp)
                                    CustomAccessibilityAction(moveUp) {
                                        onMove(-1)
                                        true
                                    }
                                else null,
                                if (canMoveDown)
                                    CustomAccessibilityAction(moveDown) {
                                        onMove(1)
                                        true
                                    }
                                else null,
                            )
                    }
                    .pointerInput(entry.id) {
                        detectVerticalDragGestures(
                            onDragStart = { dragStart() },
                            onDragEnd = { dragEnd() },
                            onDragCancel = { dragCancel() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                drag(amount)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        content = { Text(stringResource(entry.title)) },
        trailingContent = { Switch(checked = true, onCheckedChange = null) },
    )
}

@Composable
private fun QuickPanelMoreGroup(hidden: List<FeatureEntry>, onAdd: (String) -> Unit) {
    QuickPanelGroup(
        stringResource(R.string.quick_panel_more),
        stringResource(R.string.quick_panel_more_hint),
    ) {
        FeatureSection.entries.forEach { section ->
            val rows = hidden.filter { it.section == section }
            if (rows.isNotEmpty()) {
                Text(
                    stringResource(section.title),
                    Modifier.padding(
                        start = Spacing.Large,
                        end = Spacing.Large,
                        top = Spacing.Medium,
                        bottom = Spacing.ExtraSmall,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEach { entry -> QuickPanelHiddenRow(entry) { onAdd(entry.id) } }
            }
        }
    }
}

@Composable
private fun QuickPanelHiddenRow(entry: FeatureEntry, onAdd: () -> Unit) {
    ListItem(
        modifier =
            Modifier.toggleable(value = false, role = Role.Switch, onValueChange = { onAdd() }),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        content = { Text(stringResource(entry.title)) },
        trailingContent = { Switch(checked = false, onCheckedChange = null) },
    )
}

/**
 * Move one panel row; ids that no longer name a feature keep their slot so the stored order is not
 * silently rewritten.
 */
private fun moveVisible(
    items: List<String>,
    from: Int,
    to: Int,
    catalog: List<FeatureEntry>,
): List<String> {
    val activeIds = catalog.map { it.id }.toSet()
    val visible = items.filter { it in activeIds }
    if (from !in visible.indices || to !in visible.indices || from == to) return items
    val reordered = visible.toMutableList().apply { add(to, removeAt(from)) }
    val queue = ArrayDeque(reordered)
    return items.map { if (it in activeIds) queue.removeFirst() else it }
}

/**
 * Opens [onOpen] after a left swipe over the content. Only watches: a drag a child already took (a
 * code block scrolling) does not count, and the drag still reaches the sessions drawer.
 */
fun Modifier.quickPanelSwipe(enabled: Boolean, onOpen: () -> Unit): Modifier =
    if (!enabled) this
    else
        pointerInput(Unit) {
            val threshold = 72.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dx = 0f
                var dy = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change: PointerInputChange =
                        event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (change.isConsumed) break
                    val delta = change.position - change.previousPosition
                    dx += delta.x
                    dy += delta.y
                    if (abs(dy) > threshold) break
                    if (-dx > threshold && -dx > 2 * abs(dy)) {
                        onOpen()
                        break
                    }
                }
            }
        }

/** 快捷入口: the configured pages, sliding in from the end edge over the chat. */
@Composable
fun QuickPanel(
    visible: Boolean,
    items: List<String>,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val dismiss by rememberUpdatedState(onDismiss)
    val contentDirection = LocalLayoutDirection.current
    LaunchedEffect(visible) {
        if (visible) {
            drawerState.open()
            snapshotFlow { drawerState.currentValue }.first { it == DrawerValue.Closed }
            dismiss()
        } else drawerState.close()
    }
    if (!visible && drawerState.isClosed) return
    // M3 draws at the start edge. Reverse the container direction, then restore it for text.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = visible,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawerState,
                    modifier = Modifier.width(280.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                        Column(Modifier.systemBarsPadding().padding(vertical = Spacing.Medium)) {
                            Text(
                                stringResource(CoreDesignsystemR.string.quick_panel_title),
                                Modifier.padding(horizontal = 16.dp, vertical = Spacing.Medium),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val catalog = LocalFeatureCatalog.current
                            val entries = items.mapNotNull { id ->
                                catalog.firstOrNull { it.id == id }
                            }
                            if (entries.isEmpty())
                                Text(
                                    stringResource(R.string.quick_panel_empty),
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            LazyColumn(Modifier.weight(1f)) {
                                items(entries, key = { it.id }) { entry ->
                                    NavigationDrawerItem(
                                        label = { Text(stringResource(entry.title)) },
                                        selected = false,
                                        onClick = { onOpen(entry.id) },
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                }
                            }
                            TextButton(onEdit, Modifier.padding(horizontal = 12.dp)) {
                                Text(stringResource(R.string.quick_panel_edit))
                            }
                        }
                    }
                }
            },
            content = {},
        )
    }
}

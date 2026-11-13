package dev.fughetta.dshcompose.ui.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.RadioListItem
import dev.fughetta.dshcompose.ui.ReadablePane
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.SwitchListItem
import dev.fughetta.dshcompose.ui.adaptive.ContentWidths
import dev.fughetta.dshcompose.ui.adaptive.RailItem
import dev.fughetta.dshcompose.ui.adaptive.SectionRailNav
import dev.fughetta.dshcompose.ui.adaptive.TwoPaneBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    SwitchListItem(label, checked, change, enabled = enabled)
}

@Composable
internal fun Choice(label: String, selected: Boolean, enabled: Boolean = true, select: () -> Unit) {
    RadioListItem(label, selected, select, enabled = enabled)
}

@Composable
internal fun Action(label: Int, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled) { Text(stringResource(label)) }
}

internal fun PhoneControlsPage.label() =
    when (this) {
        PhoneControlsPage.Overview -> R.string.phone_controls_overview
        PhoneControlsPage.Access -> R.string.phone_controls_access
        PhoneControlsPage.Rules -> R.string.phone_controls_rules
        PhoneControlsPage.Unlocks -> R.string.phone_controls_unlocks
        PhoneControlsPage.Log -> R.string.phone_controls_log
        PhoneControlsPage.Background -> R.string.phone_controls_background
    }

internal fun PhoneAppRule.label() =
    when (this) {
        PhoneAppRule.Allow -> R.string.phone_controls_allow
        PhoneAppRule.Confirm -> R.string.phone_controls_confirm
        PhoneAppRule.Deny -> R.string.phone_controls_deny
    }

@Composable
internal fun modeLabel(mode: String) =
    when (mode) {
        "off" -> stringResource(R.string.phone_controls_mode_off)
        "root" -> stringResource(R.string.phone_controls_mode_root)
        "adb" -> stringResource(R.string.phone_controls_mode_adb)
        "shizuku" -> "Shizuku"
        "sui" -> "Sui"
        else -> mode
    }

/** No accessibility click action: removing these protections requires a real 1.5 second hold. */
@Composable
internal fun HoldToConfirm(enabled: Boolean, confirm: () -> Unit) {
    val latestConfirm by rememberUpdatedState(confirm)
    val label = stringResource(R.string.phone_controls_hold_confirm)
    val hint = stringResource(R.string.phone_controls_hold_hint)
    Box(
        Modifier.background(
                if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
                MaterialTheme.shapes.medium,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = hint
                role = Role.Button
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (enabled)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val held =
                            withTimeoutOrNull(1_500L) {
                                var pressed = true
                                while (pressed) {
                                    val change =
                                        awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    pressed =
                                        change?.pressed == true &&
                                            change.position.x >= 0 &&
                                            change.position.y >= 0 &&
                                            change.position.x < size.width &&
                                            change.position.y < size.height
                                    change?.consume()
                                }
                                false
                            } ?: true
                        if (held) latestConfirm()
                    }
            }
            .padding(Spacing.Large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color =
                if (enabled) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Phone control state is read only while one of its pages is on screen. */
@Composable
internal fun ObservePhoneControls(onAction: (PhoneControlsAction) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestAction by rememberUpdatedState(onAction)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latestAction(PhoneControlsAction.Observe(true))
            if (event == Lifecycle.Event.ON_PAUSE) latestAction(PhoneControlsAction.Observe(false))
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            latestAction(PhoneControlsAction.Observe(true))
        onDispose {
            lifecycle.removeObserver(observer)
            latestAction(PhoneControlsAction.Observe(false))
        }
    }
}

/** Pages of phone control with their own screen, opened from the overview. */
enum class PhoneSubpage {
    Dnd,
    Unlocks,
    BackgroundGuide,
}

/** The tabs of the phone control page; unlocks and privileged control have their own pages. */
internal val PhoneControlsTabs =
    listOf(
        PhoneControlsPage.Overview,
        PhoneControlsPage.Access,
        PhoneControlsPage.Rules,
        PhoneControlsPage.Log,
    )

@Composable
internal fun SubpageLink(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        content = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_right), null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Hosts the tab pages: on wide windows a rail of pages sits left of a [ContentWidths.Form] content
 * column, while narrow windows keep the single phone column.
 */
@Composable
internal fun PhoneControlsPane(
    modifier: Modifier,
    wide: Boolean,
    railWidth: Dp,
    rail: @Composable ColumnScope.() -> Unit,
    content: @Composable (modifier: Modifier, padding: PaddingValues) -> Unit,
) {
    if (wide) {
        TwoPaneBoard(
            modifier = modifier,
            railWidth = railWidth,
            rail = rail,
            content = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    content(
                        Modifier.fillMaxHeight().widthIn(max = ContentWidths.Form).fillMaxWidth(),
                        PaddingValues(Spacing.Large),
                    )
                }
            },
        )
    } else {
        ReadablePane(modifier) { inset ->
            content(Modifier.fillMaxSize(), PaddingValues(inset, Spacing.Large))
        }
    }
}

/** Secondary navigation for the phone-control pages on wide windows. */
@Composable
internal fun PhoneControlsRail(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
) {
    val selected = if (state.page in PhoneControlsTabs) state.page else PhoneControlsTabs.first()
    SectionRailNav(
        items = PhoneControlsTabs.map { RailItem(it.name, stringResource(it.label())) },
        selectedId = selected.name,
        onSelect = { id ->
            PhoneControlsPage.entries
                .firstOrNull { it.name == id }
                ?.let { page -> onAction(PhoneControlsAction.Page(page)) }
        },
    )
}

/** Confirmation policy choices shared by the phone column and the wide control card. */
@Composable
internal fun ConfirmationChoices(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
    heading: Boolean,
) {
    if (heading)
        Text(
            stringResource(R.string.phone_controls_confirmation),
            style = MaterialTheme.typography.titleMedium,
        )
    listOf(
            "ask" to R.string.phone_controls_confirm_ask,
            "task" to R.string.phone_controls_confirm_task,
            "always" to R.string.phone_controls_confirm_always,
        )
        .forEach { (mode, label) ->
            val selected = state.confirmMode == mode
            Card(
                modifier =
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).selectable(
                        selected,
                        enabled = !state.busy,
                        role = Role.RadioButton,
                    ) {
                        onAction(PhoneControlsAction.ConfirmMode(mode))
                    },
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                    ),
            ) {
                Row(
                    Modifier.padding(Spacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                ) {
                    RadioButton(selected, onClick = null, enabled = !state.busy)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                when (mode) {
                                    "task" -> R.string.phone_confirm_task_help
                                    "always" -> R.string.phone_confirm_always_help
                                    else -> R.string.phone_confirm_ask_help
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (mode == "always")
                        Icon(
                            painterResource(R.drawable.ic_info),
                            stringResource(R.string.phone_risk_notice),
                            tint = MaterialTheme.colorScheme.error,
                        )
                }
            }
        }
}

/** Help and recovery text under the accessibility row. */
@Composable
internal fun AccessibilityNotes(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
        Text(stringResource(R.string.phone_controls_accessibility_help))
        if (state.accessibilityAttemptFailed) {
            Text(stringResource(R.string.phone_controls_restricted_help))
            Action(R.string.phone_controls_app_details) {
                onAction(PhoneControlsAction.OpenAccess(PhoneAccessKind.AppDetails))
            }
        }
    }
}

@Composable
internal fun PhoneHelp(title: String, body: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_s_help), null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.Small))
            Text(title)
        }
        if (expanded)
            Text(
                body,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
internal fun PhoneAccessGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            Modifier.padding(start = 4.dp, top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun PhoneAccessDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f),
    )
}

@Composable
internal fun PhoneAccessLink(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            null,
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PhoneAccessRow(
    label: Int,
    granted: Boolean,
    kind: PhoneAccessKind,
    busy: Boolean,
    onAction: (PhoneControlsAction) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = !busy) { onAction(PhoneControlsAction.OpenAccess(kind)) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(
                if (granted) R.string.phone_controls_granted else R.string.phone_access_grant,
            ),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun VendorSettingCard(
    kind: PhoneAccessKind,
    label: Int,
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
) {
    val reviewed = kind in state.access.vendorVisited
    var expanded by rememberSaveable(kind, reviewed) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(label),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    if (reviewed) R.string.phone_setting_done_short
                    else R.string.phone_setting_pending_short,
                ),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (reviewed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
            )
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                null,
                Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(
                    onClick = { onAction(PhoneControlsAction.OpenAccess(kind)) },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.phone_setting_open))
                }
                TextButton(
                    onClick = { onAction(PhoneControlsAction.MarkVendorVisited(kind, !reviewed)) },
                    enabled = !state.busy,
                ) {
                    Text(
                        stringResource(
                            if (reviewed) R.string.phone_setting_recheck
                            else R.string.phone_controls_vendor_reviewed,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PhoneAppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap by
        produceState<ImageBitmap?>(null, packageName) {
            value =
                withContext(Dispatchers.IO) {
                    try {
                        context.packageManager
                            .getApplicationIcon(packageName)
                            .toBitmap(96, 96)
                            .asImageBitmap()
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                        null
                    }
                }
        }
    bitmap?.let { Image(it, null, Modifier.size(40.dp)) }
        ?: Icon(painterResource(R.drawable.ic_s_phone), null, Modifier.size(40.dp))
}

@Composable
internal fun AppRuleBadge(rule: PhoneAppRule) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color =
            when (rule) {
                PhoneAppRule.Deny -> colors.errorContainer
                PhoneAppRule.Confirm -> colors.tertiaryContainer
                PhoneAppRule.Allow -> colors.primaryContainer
            },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(rule.label()),
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
internal fun PhoneAppPicker(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.phone_select_apps)) },
        text = {
            Column {
                OutlinedTextField(
                    state.query,
                    { onAction(PhoneControlsAction.Query(it)) },
                    label = { Text(stringResource(R.string.phone_controls_search)) },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(state.apps.filter { it.installed }, key = { it.packageName }) { app ->
                        ListItem(
                            content = { Text(app.label) },
                            supportingContent = { Text(app.packageName) },
                            leadingContent = { PhoneAppIcon(app.packageName) },
                            modifier =
                                Modifier.clickable {
                                    onAction(PhoneControlsAction.Query(app.packageName))
                                    dismiss()
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

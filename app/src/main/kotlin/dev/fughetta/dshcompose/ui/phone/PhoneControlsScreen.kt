package dev.fughetta.dshcompose.ui.phone

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.NarrowControlWidth
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.SectionCard
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.atLeastLarge
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import dev.fughetta.dshcompose.ui.shell.AskAiTopic
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneControlsScreen(
    state: PhoneControlsUiState,
    onAction: (PhoneControlsAction) -> Unit,
    claimPermission: (String) -> Boolean,
    silencedRisks: Set<String> = emptySet(),
    onSilenceRisk: (String) -> Unit = {},
    onAskAi: ((AskAiTopic) -> Unit)? = null,
    initialSubpage: PhoneSubpage? = null,
    backgroundGuideSeen: Boolean = true,
    onBackgroundGuideSeen: () -> Unit = {},
) {
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var appPicker by rememberSaveable { mutableStateOf(false) }
    var a11yRestore by rememberSaveable { mutableStateOf(false) }
    var subpage by rememberSaveable { mutableStateOf(initialSubpage) }
    // A page opened directly (a deep link) leaves with the system back; one opened from here
    // returns here.
    BackHandler(subpage != null && subpage != initialSubpage) { subpage = null }
    val onOpenDnd = { subpage = PhoneSubpage.Dnd }
    val onOpenUnlocks = { subpage = PhoneSubpage.Unlocks }
    val onOpenBackgroundGuide = { subpage = PhoneSubpage.BackgroundGuide }
    ObservePhoneControls(onAction)
    val latestAction by rememberUpdatedState(onAction)
    var permissionId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            grants ->
            latestAction(
                PhoneControlsAction.PermissionResult(permissionId, grants.values.all { it }),
            )
            permissionId = null
        }
    LaunchedEffect(state.platformRequest?.id) {
        state.platformRequest?.let { request ->
            if (claimPermission(request.id)) {
                permissionId = request.id
                try {
                    launcher.launch(request.permissions.toTypedArray())
                } catch (_: Exception) {
                    latestAction(PhoneControlsAction.PermissionResult(request.id, false))
                    permissionId = null
                }
            }
        }
    }
    when (subpage) {
        PhoneSubpage.Dnd -> PhoneDndScreen(state, onAction, onAskAi)
        PhoneSubpage.Unlocks -> PhoneUnlocksScreen(state, onAction, onAskAi)
        PhoneSubpage.BackgroundGuide -> BackgroundGuideScreen(state, onAction)
        null -> {
            val tier = windowTier()
            val wide = tier.atLeastExpanded
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    Column {
                        TopAppBar(
                            title = { Text(stringResource(R.string.navigation_phone_controls)) },
                            navigationIcon = { UpButton() },
                            actions = {
                                IconButton(
                                    onClick = { onAction(PhoneControlsAction.Refresh) },
                                    enabled = !state.busy,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_refresh),
                                        stringResource(R.string.phone_controls_refresh),
                                    )
                                }
                            },
                        )
                        // Wide windows move the page switcher to the rail beside the content.
                        if (!wide) {
                            val scroll = rememberScrollState()
                            Box {
                                PrimaryScrollableTabRow(
                                    selectedTabIndex =
                                        PhoneControlsTabs.indexOf(state.page).coerceAtLeast(0),
                                    edgePadding = 16.dp,
                                    scrollState = scroll,
                                ) {
                                    PhoneControlsTabs.forEach { page ->
                                        Tab(
                                            state.page == page,
                                            { onAction(PhoneControlsAction.Page(page)) },
                                            text = {
                                                Text(stringResource(page.label()), maxLines = 1)
                                            },
                                        )
                                    }
                                }
                                val surface = MaterialTheme.colorScheme.surface
                                if (scroll.canScrollBackward)
                                    Box(
                                        Modifier.align(Alignment.CenterStart)
                                            .width(20.dp)
                                            .height(48.dp)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(surface, Color.Transparent),
                                                ),
                                            ),
                                    )
                                if (scroll.canScrollForward)
                                    Box(
                                        Modifier.align(Alignment.CenterEnd)
                                            .width(20.dp)
                                            .height(48.dp)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(Color.Transparent, surface),
                                                ),
                                            ),
                                    )
                            }
                        }
                        if (state.loading || state.busy)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
            ) { padding ->
                PhoneControlsPane(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    wide = wide,
                    railWidth = if (tier.atLeastLarge) 244.dp else 208.dp,
                    rail = { PhoneControlsRail(state, onAction) },
                ) { modifier, listPadding ->
                    LazyColumn(
                        modifier,
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
                    ) {
                        state.error?.let { error ->
                            item("error") { Text(error, color = MaterialTheme.colorScheme.error) }
                        }
                        state.notice?.let { notice -> item("notice") { Text(notice) } }
                        when (state.page) {
                            PhoneControlsPage.Overview -> {
                                item("enabled") {
                                    ElevatedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(Spacing.Large)) {
                                            Toggle(
                                                stringResource(R.string.phone_controls_enable),
                                                state.status.enabled,
                                                !state.busy,
                                            ) {
                                                onAction(PhoneControlsAction.Enabled(it))
                                            }
                                            Text(
                                                stringResource(
                                                    if (state.status.bridgeListening)
                                                        R.string.phone_controls_running
                                                    else R.string.phone_controls_waiting,
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                item("access") {
                                    ElevatedCard(
                                        onClick = {
                                            onAction(
                                                PhoneControlsAction.Page(PhoneControlsPage.Access),
                                            )
                                        },
                                        enabled = !state.busy,
                                    ) {
                                        ListItem(
                                            content = {
                                                Text(
                                                    stringResource(
                                                        if (state.access.accessibility)
                                                            R.string.phone_access_ready
                                                        else R.string.phone_access_needed,
                                                    ),
                                                )
                                            },
                                            supportingContent = {
                                                Text(stringResource(R.string.phone_access_review))
                                            },
                                            trailingContent = {
                                                Icon(
                                                    painterResource(R.drawable.ic_chevron_right),
                                                    null,
                                                )
                                            },
                                        )
                                    }
                                }
                                item("subpages") {
                                    Column {
                                        SubpageLink(
                                            stringResource(R.string.phone_dnd_title),
                                            dndSummary(state.status),
                                            onOpenDnd,
                                        )
                                        SubpageLink(
                                            stringResource(R.string.phone_controls_unlocks),
                                            stringResource(R.string.phone_unlocks_subtitle),
                                            onOpenUnlocks,
                                        )
                                        SubpageLink(
                                            stringResource(R.string.background_guide_title),
                                            stringResource(R.string.background_guide_subtitle),
                                            onOpenBackgroundGuide,
                                        )
                                        onAskAi?.let { ask ->
                                            SubpageLink(
                                                stringResource(R.string.ask_ai),
                                                stringResource(AskAiTopic.PhoneControl.question),
                                            ) {
                                                ask(AskAiTopic.PhoneControl)
                                            }
                                        }
                                    }
                                }
                                if (wide) {
                                    item("policy") {
                                        SectionCard(
                                            title =
                                                stringResource(
                                                    R.string.phone_controls_confirmation,
                                                ),
                                        ) {
                                            Column(
                                                Modifier.selectableGroup()
                                                    .padding(horizontal = Spacing.Medium),
                                            ) {
                                                ConfirmationChoices(
                                                    state,
                                                    onAction,
                                                    heading = false,
                                                )
                                                HorizontalDivider(
                                                    Modifier.padding(vertical = Spacing.Small),
                                                )
                                                Toggle(
                                                    stringResource(R.string.phone_controls_return),
                                                    state.returnAfterDone,
                                                    !state.busy,
                                                ) {
                                                    onAction(
                                                        PhoneControlsAction.ReturnAfterDone(it),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    item("confirm") {
                                        Column(Modifier.selectableGroup()) {
                                            ConfirmationChoices(state, onAction, heading = true)
                                        }
                                    }
                                    item("return") {
                                        Toggle(
                                            stringResource(R.string.phone_controls_return),
                                            state.returnAfterDone,
                                            !state.busy,
                                        ) {
                                            onAction(PhoneControlsAction.ReturnAfterDone(it))
                                        }
                                    }
                                }
                                if (state.status.task.active || state.status.task.stopped)
                                    item("task") {
                                        Column {
                                            Text(
                                                state.status.task.app
                                                    ?: stringResource(R.string.phone_controls_task),
                                            )
                                            if (state.status.task.paused)
                                                Text(stringResource(R.string.phone_controls_paused))
                                            if (state.status.task.waived)
                                                Text(stringResource(R.string.phone_controls_waived))
                                            if (state.status.task.active)
                                                Action(R.string.phone_controls_stop) {
                                                    onAction(PhoneControlsAction.StopTask)
                                                }
                                        }
                                    }
                                item("read") {
                                    Button(
                                        onClick = { onAction(PhoneControlsAction.TestScreen) },
                                        enabled = !state.busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.phone_controls_test_screen))
                                    }
                                }
                            }
                            PhoneControlsPage.Access -> {
                                item("core") {
                                    PhoneAccessGroup(
                                        stringResource(R.string.phone_core_permissions),
                                    ) {
                                        PhoneAccessRow(
                                            R.string.phone_controls_accessibility,
                                            state.access.accessibility,
                                            PhoneAccessKind.Accessibility,
                                            state.busy,
                                            onAction,
                                        )
                                        if (state.accessibilityAttemptFailed) {
                                            Column(
                                                Modifier.padding(
                                                    horizontal = 16.dp,
                                                    vertical = 8.dp,
                                                ),
                                            ) {
                                                AccessibilityNotes(state, onAction)
                                            }
                                        }
                                        PhoneAccessDivider()
                                        PhoneAccessRow(
                                            R.string.phone_controls_notifications,
                                            state.access.notifications,
                                            PhoneAccessKind.Notifications,
                                            state.busy,
                                            onAction,
                                        )
                                        PhoneAccessDivider()
                                        PhoneAccessRow(
                                            R.string.phone_controls_overlay_permission,
                                            state.access.overlay,
                                            PhoneAccessKind.Overlay,
                                            state.busy,
                                            onAction,
                                        )
                                        PhoneAccessDivider()
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Text(
                                                stringResource(R.string.phone_files_title),
                                                Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Switch(
                                                checked = state.phoneStorage,
                                                onCheckedChange = {
                                                    onAction(PhoneControlsAction.Storage(it))
                                                },
                                                enabled = !state.busy,
                                            )
                                        }
                                        PhoneHelp(
                                            stringResource(R.string.phone_storage_explanation),
                                            stringResource(R.string.phone_controls_storage_help),
                                        )
                                        if (state.needsAllFiles) {
                                            TextButton(
                                                onClick = {
                                                    onAction(
                                                        PhoneControlsAction.OpenAccess(
                                                            PhoneAccessKind.AllFiles,
                                                        ),
                                                    )
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                                enabled = !state.busy,
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.phone_controls_all_files,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                                item("background") {
                                    PhoneAccessGroup(
                                        stringResource(R.string.phone_background_settings),
                                    ) {
                                        PhoneAccessRow(
                                            R.string.phone_controls_battery,
                                            state.access.battery,
                                            PhoneAccessKind.Battery,
                                            state.busy,
                                            onAction,
                                        )
                                        if (state.access.vendor != null) {
                                            listOf(
                                                    PhoneAccessKind.VendorBackground to
                                                        R.string.phone_controls_vendor_background,
                                                    PhoneAccessKind.Autostart to
                                                        R.string.phone_controls_autostart,
                                                    PhoneAccessKind.Popup to
                                                        R.string.phone_controls_popup,
                                                )
                                                .forEach { (kind, label) ->
                                                    PhoneAccessDivider()
                                                    VendorSettingCard(kind, label, state, onAction)
                                                }
                                        }
                                    }
                                    if (state.access.vendor != null)
                                        Text(
                                            stringResource(R.string.phone_controls_vendor_help),
                                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                }
                                item("assistance") {
                                    PhoneAccessGroup(
                                        stringResource(R.string.phone_access_assistance),
                                    ) {
                                        PhoneAccessLink(
                                            stringResource(R.string.a11y_restore_title),
                                            stringResource(
                                                if (state.status.keeperGranted)
                                                    R.string.a11y_restore_on
                                                else R.string.a11y_restore_needs_grant,
                                            ),
                                        ) {
                                            a11yRestore = true
                                        }
                                        PhoneAccessDivider()
                                        PhoneAccessLink(
                                            stringResource(R.string.background_guide_title),
                                            stringResource(R.string.background_guide_subtitle),
                                            onOpenBackgroundGuide,
                                        )
                                    }
                                }
                            }
                            PhoneControlsPage.Rules -> {
                                item("search") {
                                    OutlinedTextField(
                                        state.query,
                                        { onAction(PhoneControlsAction.Query(it)) },
                                        Modifier.fillMaxWidth(),
                                        label = {
                                            Text(stringResource(R.string.phone_controls_search))
                                        },
                                        singleLine = true,
                                    )
                                }
                                item("selectApps") {
                                    Action(R.string.phone_select_apps, !state.busy) {
                                        onAction(PhoneControlsAction.Query(""))
                                        appPicker = true
                                    }
                                    if (
                                        state.query.contains('.') &&
                                            state.apps.none { it.packageName == state.query.trim() }
                                    ) {
                                        Action(R.string.phone_controls_add_package, !state.busy) {
                                            onAction(PhoneControlsAction.AddPackage)
                                        }
                                    }
                                }
                                item("ruleshelp") {
                                    Text(stringResource(R.string.phone_controls_rules_help))
                                    Action(R.string.phone_controls_reset_rules, !state.busy) {
                                        onAction(PhoneControlsAction.ResetRules)
                                    }
                                }
                                items(
                                    state.apps,
                                    key = { it.packageName },
                                    contentType = { "appRule" },
                                ) { app ->
                                    Column {
                                        ListItem(
                                            content = {
                                                Text(
                                                    app.label,
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                            },
                                            supportingContent = {
                                                Text(
                                                    app.packageName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            },
                                            leadingContent = { PhoneAppIcon(app.packageName) },
                                            trailingContent = { AppRuleBadge(app.rule) },
                                        )
                                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                                            if (
                                                maxWidth <
                                                    NarrowControlWidth *
                                                        LocalDensity.current.fontScale
                                                            .coerceAtLeast(1f)
                                            ) {
                                                var menu by remember { mutableStateOf(false) }
                                                Box {
                                                    TextButton(
                                                        onClick = { menu = true },
                                                        enabled = !state.busy,
                                                        modifier =
                                                            Modifier.fillMaxWidth()
                                                                .padding(top = Spacing.Small),
                                                    ) {
                                                        Text(stringResource(app.rule.label()))
                                                    }
                                                    DropdownMenu(
                                                        menu,
                                                        onDismissRequest = { menu = false },
                                                    ) {
                                                        PhoneAppRule.entries.forEach { rule ->
                                                            DropdownMenuItem(
                                                                text = {
                                                                    Text(
                                                                        stringResource(
                                                                            rule.label(),
                                                                        ),
                                                                    )
                                                                },
                                                                enabled = !state.busy,
                                                                leadingIcon = {
                                                                    RadioButton(
                                                                        app.rule == rule,
                                                                        onClick = null,
                                                                        enabled = !state.busy,
                                                                    )
                                                                },
                                                                onClick = {
                                                                    menu = false
                                                                    onAction(
                                                                        PhoneControlsAction.AppRule(
                                                                            app.packageName,
                                                                            rule,
                                                                        ),
                                                                    )
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                SingleChoiceSegmentedButtonRow(
                                                    Modifier.fillMaxWidth()
                                                        .padding(top = Spacing.Small),
                                                ) {
                                                    PhoneAppRule.entries.forEachIndexed {
                                                        index,
                                                        rule ->
                                                        SegmentedButton(
                                                            app.rule == rule,
                                                            {
                                                                onAction(
                                                                    PhoneControlsAction.AppRule(
                                                                        app.packageName,
                                                                        rule,
                                                                    ),
                                                                )
                                                            },
                                                            SegmentedButtonDefaults.itemShape(
                                                                index,
                                                                PhoneAppRule.entries.size,
                                                            ),
                                                            enabled = !state.busy,
                                                        ) {
                                                            Text(stringResource(rule.label()))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            PhoneControlsPage.Log -> {
                                item("privacy") {
                                    PhoneHelp(
                                        stringResource(R.string.phone_log_privacy),
                                        stringResource(R.string.phone_controls_log_help),
                                    )
                                }
                                item("filter") {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                                    ) {
                                        FilterChip(
                                            selected = !recentOnly,
                                            onClick = { recentOnly = false },
                                            label = {
                                                Text(stringResource(R.string.phone_log_all))
                                            },
                                        )
                                        FilterChip(
                                            selected = recentOnly,
                                            onClick = { recentOnly = true },
                                            label = {
                                                Text(stringResource(R.string.phone_log_recent))
                                            },
                                        )
                                    }
                                }
                                val visibleLog =
                                    state.log.filter {
                                        !recentOnly ||
                                            it.time >= System.currentTimeMillis() - 3_600_000
                                    }
                                if (visibleLog.isEmpty())
                                    item("empty") {
                                        Column(
                                            Modifier.fillMaxWidth().padding(Spacing.Large),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement =
                                                Arrangement.spacedBy(Spacing.Medium),
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_s_doc),
                                                null,
                                                Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                stringResource(R.string.phone_controls_log_empty),
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                stringResource(
                                                    if (recentOnly) R.string.phone_log_no_recent
                                                    else R.string.phone_log_start,
                                                ),
                                            )
                                        }
                                    }
                                items(visibleLog, contentType = { "actionLog" }) { row ->
                                    Column {
                                        Text(
                                            "${DateFormat.getTimeInstance().format(Date(row.time))} · ${row.tool}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        row.app?.let { Text(it) }
                                        Text(row.summary)
                                        Text(
                                            row.decision,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            // Standalone pages; the tab row does not offer them.
                            PhoneControlsPage.Unlocks,
                            PhoneControlsPage.Background -> Unit
                        }
                    }
                }
            }
        }
    }
    if (appPicker) PhoneAppPicker(state, onAction) { appPicker = false }
    PhoneRiskDialog(state, onAction, silencedRisks, onSilenceRisk, onAskAi)
    state.screenText?.let { text ->
        ScreenTextSheet(text) { onAction(PhoneControlsAction.CloseScreenText) }
    }
    if (a11yRestore)
        A11yRestoreSheet(
            state,
            onAction,
            onSetup = {
                a11yRestore = false
                subpage = PhoneSubpage.Dnd
            },
        ) {
            a11yRestore = false
        }
    if (state.status.enabled && !backgroundGuideSeen)
        BackgroundGuideDialog(state.access.vendor, onAction, onBackgroundGuideSeen)
}

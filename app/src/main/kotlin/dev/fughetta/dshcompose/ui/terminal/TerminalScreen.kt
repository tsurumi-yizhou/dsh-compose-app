package dev.fughetta.dshcompose.ui.terminal

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.ActionIcon
import dev.fughetta.dshcompose.ui.OverflowMenu
import dev.fughetta.dshcompose.ui.Spacing
import dev.fughetta.dshcompose.ui.UpButton
import dev.fughetta.dshcompose.ui.adaptive.atLeastExpanded
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalState,
    onAction: (TerminalAction) -> Unit,
    onOpenExternal: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LifecycleStartEffect(state.hostKey, state.sessionId) {
        onAction(TerminalAction.Observe(true))
        onStopOrDispose { onAction(TerminalAction.Observe(false)) }
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val wide = windowTier().atLeastExpanded
    var localError by remember { mutableStateOf<String?>(null) }
    var keyboard by remember { mutableStateOf(true) }
    var shortcutsOpen by rememberSaveable { mutableStateOf(true) }
    var link by remember(state.hostKey, state.sessionId) { mutableStateOf<String?>(null) }
    val currentState by rememberUpdatedState(state)
    val dispatch by rememberUpdatedState(onAction)
    val modifiers = remember {
        object : org.connectbot.terminal.ModifierManager {
            override fun isCtrlActive() = currentState.ctrl != 0

            override fun isAltActive() = currentState.alt != 0

            override fun isShiftActive() = false

            override fun clearTransients() {
                dispatch(TerminalAction.ConsumeModifiers)
            }
        }
    }
    val selected = state.selected
    val paste: () -> Unit = {
        scope.launch {
            clipboard.getClipEntry()?.clipData?.let { clip ->
                if (clip.itemCount > 0)
                    onAction(TerminalAction.Paste(clip.getItemAt(0).text?.toString().orEmpty()))
            }
        }
    }
    // The terminal keeps the full window width; only chrome moves into the app bar.
    Scaffold(
        modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.navigation_terminal)) },
                    navigationIcon = { UpButton() },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            scrolledContainerColor =
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        ),
                    actions = {
                        if (selected != null)
                            ActionIcon(
                                R.drawable.ic_content_paste,
                                stringResource(R.string.terminal_paste),
                                enabled = selected.writable,
                                onClick = paste,
                            )
                        IconToggleButton(
                            checked = keyboard,
                            onCheckedChange = { keyboard = it },
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_keyboard),
                                stringResource(R.string.terminal_keyboard),
                            )
                        }
                        ActionIcon(
                            R.drawable.ic_add,
                            stringResource(R.string.terminal_new),
                            enabled =
                                state.supported &&
                                    state.sessionId != null &&
                                    !state.loading &&
                                    !state.busy,
                        ) {
                            onAction(TerminalAction.New())
                        }
                        // When the shortcut bar is collapsed, its only affordance moves up here.
                        if (selected != null && !shortcutsOpen)
                            IconButton(onClick = { shortcutsOpen = true }) {
                                Icon(
                                    painterResource(R.drawable.ic_arrow_downward),
                                    contentDescription =
                                        stringResource(R.string.terminal_shortcuts_show),
                                )
                            }
                        OverflowMenu { close ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_refresh)) },
                                enabled = !state.loading && !state.busy,
                                onClick = {
                                    close()
                                    onAction(TerminalAction.Refresh)
                                },
                            )
                            if (selected != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_reconnect)) },
                                    enabled = !state.busy,
                                    onClick = {
                                        close()
                                        onAction(TerminalAction.Reconnect(selected.id))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_rename)) },
                                    enabled = !state.busy,
                                    onClick = {
                                        close()
                                        onAction(TerminalAction.RequestRename(selected.id))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.terminal_close)) },
                                    enabled = !state.busy,
                                    onClick = {
                                        close()
                                        onAction(TerminalAction.RequestClose(selected.id))
                                    },
                                )
                            }
                        }
                    },
                )
                val tabIndex = state.tabs.indexOfFirst { it.id == state.selectedId }
                if (state.tabs.isNotEmpty())
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tabIndex.coerceAtLeast(0),
                        edgePadding = Spacing.Large,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ) {
                        state.tabs.forEach { tab ->
                            Tab(
                                tab.id == state.selectedId,
                                { onAction(TerminalAction.Select(tab.id)) },
                                text = {
                                    Text(
                                        tab.title.ifBlank { tab.info?.shell?.name ?: tab.id },
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            if (selected != null && shortcutsOpen)
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentPadding = PaddingValues(horizontal = Spacing.Small),
                    actions = {
                        IconButton(onClick = { shortcutsOpen = false }) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_downward),
                                contentDescription =
                                    stringResource(R.string.terminal_shortcuts_hide),
                                modifier = Modifier.rotate(180f),
                            )
                        }
                        LazyRow(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
                        ) {
                            item {
                                ModifierChip(
                                    "Ctrl",
                                    state.ctrl,
                                    { onAction(TerminalAction.ModifierKey(true)) },
                                    { onAction(TerminalAction.ModifierKey(true, true)) },
                                )
                            }
                            item {
                                ModifierChip(
                                    "Alt",
                                    state.alt,
                                    { onAction(TerminalAction.ModifierKey(false)) },
                                    { onAction(TerminalAction.ModifierKey(false, true)) },
                                )
                            }
                            items(
                                listOf(
                                    "Esc" to "\u001b",
                                    "Tab" to "\t",
                                    "←" to "\u001b[D",
                                    "↑" to "\u001b[A",
                                    "↓" to "\u001b[B",
                                    "→" to "\u001b[C",
                                    "|" to "|",
                                    "~" to "~",
                                    "/" to "/",
                                    "-" to "-",
                                ),
                            ) { (label, value) ->
                                OutlinedButton(
                                    onClick = {
                                        val special =
                                            when (label) {
                                                "Esc" -> org.connectbot.terminal.VTermKey.ESCAPE
                                                "Tab" -> org.connectbot.terminal.VTermKey.TAB
                                                "←" -> org.connectbot.terminal.VTermKey.LEFT
                                                "↑" -> org.connectbot.terminal.VTermKey.UP
                                                "↓" -> org.connectbot.terminal.VTermKey.DOWN
                                                "→" -> org.connectbot.terminal.VTermKey.RIGHT
                                                else -> null
                                            }
                                        onAction(
                                            if (special != null) TerminalAction.SpecialKey(special)
                                            else TerminalAction.Key(value),
                                        )
                                    },
                                    enabled = selected.writable,
                                    contentPadding = PaddingValues(Spacing.Medium),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    },
                )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(
                    horizontal = if (wide) 0.dp else Spacing.Medium,
                    vertical = Spacing.Small,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            if (!state.supported) Text(stringResource(R.string.terminal_unsupported))
            state.error?.let {
                Text(terminalErrorText(it), color = MaterialTheme.colorScheme.error)
            }
            localError?.let { Text(terminalErrorText(it), color = MaterialTheme.colorScheme.error) }
            if (state.shells.isNotEmpty())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    items(state.shells, key = { it.path }) { shell ->
                        FilterChip(
                            selected = state.shellPath == shell.path,
                            onClick = { onAction(TerminalAction.ChooseShell(shell.path)) },
                            label = { Text(shell.name) },
                        )
                    }
                }
            if (selected != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        stringResource(
                            when (selected.phase) {
                                TerminalPhase.Starting -> R.string.terminal_starting
                                TerminalPhase.Connecting -> R.string.terminal_connecting
                                TerminalPhase.Live ->
                                    if (selected.writable) R.string.terminal_live
                                    else R.string.terminal_read_only
                                TerminalPhase.Disconnected -> R.string.terminal_disconnected
                                TerminalPhase.Ended -> R.string.terminal_ended
                                TerminalPhase.Missing -> R.string.terminal_missing
                                TerminalPhase.Failed -> R.string.terminal_failed
                            },
                        ),
                    )
                    selected.info?.exitCode?.let { Text("($it)") }
                }
                selected.error?.let {
                    Text(terminalErrorText(it), color = MaterialTheme.colorScheme.error)
                }
                val emulator = selected.emulator
                if (emulator != null)
                    key(emulator) {
                        org.connectbot.terminal.Terminal(
                            terminalEmulator = emulator,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            initialFontSize = 13.sp,
                            keyboardEnabled =
                                selected.writable && selected.phase == TerminalPhase.Live,
                            modifierManager = modifiers,
                            showSoftKeyboard = keyboard,
                            onHyperlinkClick = { address ->
                                if (
                                    runCatching {
                                            android.net.Uri.parse(address).let {
                                                it.scheme == "https" &&
                                                    !it.host.isNullOrBlank() &&
                                                    it.userInfo == null
                                            }
                                        }
                                        .getOrDefault(false)
                                )
                                    link = address
                            },
                            onPasteRequest = {
                                scope.launch {
                                    clipboard.getClipEntry()?.clipData?.let { clip ->
                                        if (clip.itemCount > 0)
                                            onAction(
                                                TerminalAction.Paste(
                                                    clip.getItemAt(0).text?.toString().orEmpty(),
                                                ),
                                            )
                                    }
                                }
                            },
                        )
                    }
                else Spacer(Modifier.weight(1f))
            } else if (!state.supported || state.loading) Spacer(Modifier.weight(1f))
            else
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement =
                        Arrangement.spacedBy(Spacing.Small, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.sessionId == null) Text(stringResource(R.string.terminal_no_session))
                    else {
                        Text(stringResource(R.string.terminal_empty_title))
                        Button(
                            onClick = { onAction(TerminalAction.New()) },
                            enabled = !state.busy,
                        ) {
                            Text(stringResource(R.string.terminal_new))
                        }
                    }
                }
        }
    }
    state.renameId?.let {
        AlertDialog(
            onDismissRequest = { onAction(TerminalAction.Dismiss) },
            title = { Text(stringResource(R.string.terminal_rename)) },
            text = {
                OutlinedTextField(
                    state.renameText,
                    { onAction(TerminalAction.RenameText(it)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(TerminalAction.SaveRename) },
                    enabled = state.renameText.isNotBlank() && !state.busy,
                ) {
                    Text(stringResource(R.string.terminal_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(TerminalAction.Dismiss) }) {
                    Text(stringResource(R.string.terminal_cancel))
                }
            },
        )
    }
    if (state.confirmClose != null)
        AlertDialog(
            onDismissRequest = { onAction(TerminalAction.Dismiss) },
            title = { Text(stringResource(R.string.terminal_close_title)) },
            text = { Text(stringResource(R.string.terminal_close_text)) },
            confirmButton = {
                TextButton(onClick = { onAction(TerminalAction.ConfirmClose) }) {
                    Text(stringResource(R.string.terminal_close))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(TerminalAction.Dismiss) }) {
                    Text(stringResource(R.string.terminal_cancel))
                }
            },
        )
    if (state.pendingClipboard != null)
        AlertDialog(
            onDismissRequest = { onAction(TerminalAction.DenyClipboard) },
            title = { Text(stringResource(R.string.terminal_clipboard_title)) },
            text = { Text(stringResource(R.string.terminal_clipboard_text)) },
            confirmButton = {
                TextButton(onClick = { onAction(TerminalAction.AllowClipboard) }) {
                    Text(stringResource(R.string.terminal_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(TerminalAction.DenyClipboard) }) {
                    Text(stringResource(R.string.terminal_cancel))
                }
            },
        )
    link?.let { address ->
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text(stringResource(R.string.terminal_open_link)) },
            text = { Text(address) },
            confirmButton = {
                TextButton(
                    onClick = {
                        link = null
                        try {
                            if (onOpenExternal != null) onOpenExternal(address)
                            else uriHandler.openUri(address)
                        } catch (_: Exception) {
                            localError = "TERMINAL_LINK_OPEN_FAILED"
                        }
                    },
                ) {
                    Text(stringResource(R.string.terminal_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { link = null }) {
                    Text(stringResource(R.string.terminal_cancel))
                }
            },
        )
    }
}

@Composable
private fun ModifierChip(label: String, state: Int, click: () -> Unit, lock: () -> Unit) {
    // The outer combinedClickable owns both tap and long press, so the chip's own onClick is a
    // no-op; FilterChip supplies the selected colour and the Badge marks the locked state.
    FilterChip(
        selected = state != 0,
        onClick = {},
        label = { Text(label) },
        trailingIcon =
            if (state == 2) {
                { Badge() }
            } else null,
        modifier =
            Modifier.heightIn(min = 48.dp).combinedClickable(onClick = click, onLongClick = lock),
    )
}

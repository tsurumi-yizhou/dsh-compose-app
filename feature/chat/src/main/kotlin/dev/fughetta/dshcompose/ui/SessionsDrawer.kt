@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.fughetta.dshcompose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceSessionOperation
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.adaptive.atLeastLarge
import dev.fughetta.dshcompose.ui.adaptive.windowTier
import dev.fughetta.dshcompose.ui.sessions.DrawerUiState
import dev.fughetta.dshcompose.ui.sessions.SessionDeletionState
import dev.fughetta.dshcompose.ui.transcript.WhaleTail

/** What the sessions list asks of the shell around it. */
class DrawerNavigation(
    val onOpen: (String) -> Unit,
    val onNew: () -> Unit,
    val onSearch: () -> Unit,
    val onWorkspaces: () -> Unit,
    val onEngine: () -> Unit,
    val onHostServices: () -> Unit,
    val onSettings: () -> Unit,
)

private sealed interface FolderDialog {
    data class Create(val ids: List<String>) : FolderDialog

    data class Edit(val folder: DrawerFolder) : FolderDialog

    data object Manage : FolderDialog
}

/**
 * The conversation list: new chat and search on top, the workspace and status filters, pinned
 * conversations, folders, then days; an identity bar at the bottom. It is the pulled-over drawer on
 * a phone and the left pane of a wide window. Long press opens actions at the touch position.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsDrawerContent(
    state: AppState,
    catalog: SessionCatalogState,
    drawerUi: DrawerUiState,
    deletion: SessionDeletionState,
    navigation: DrawerNavigation,
    onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    val blocks =
        remember(state, catalog, drawerUi, hidden) {
            state
                .drawerBlocks(catalog, drawerUi)
                ?.map { block ->
                    val rows = block.items.filterNot { it.sessionId in hidden }
                    when (block) {
                        is DrawerBlock.Pinned -> block.copy(items = rows)
                        is DrawerBlock.Folder -> block.copy(items = rows)
                        is DrawerBlock.Day -> block.copy(items = rows)
                        is DrawerBlock.Filtered -> block.copy(items = rows)
                    }
                }
                ?.filterNot {
                    (it is DrawerBlock.Pinned || it is DrawerBlock.Day) && it.items.isEmpty()
                }
        }
    val selection = drawerUi.selection
    val current = state.selectedSessionId
    // The list pane of a large window is wide enough for a metadata line per row.
    val detailed = windowTier().atLeastLarge
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<DrawerNotice?>(null) }
    fun drawer(action: DrawerAction) = onAction(AppAction.Drawer(action))
    val pinnedIds = state.workspaces.pinned
    fun archive(ids: List<String>) {
        if (ids.isEmpty()) return
        hidden = hidden + ids
        notice =
            DrawerNotice(
                text = R.string.drawer_archived_notice,
                arg = ids.size,
                onUndo = { hidden = hidden - ids.toSet() },
                onCommit = {
                    ids.forEach {
                        onAction(
                            AppAction.ManageWorkspaceSession(it, WorkspaceSessionOperation.Archive),
                        )
                    }
                    hidden = hidden - ids.toSet()
                },
            )
    }
    Column(modifier.fillMaxSize()) {
        if (selection.active)
            SelectionHeader(
                selection.ids.orEmpty().size,
                onAll = { drawer(DrawerAction.SelectAll(drawerSelectableIds(blocks.orEmpty()))) },
                onClose = { drawer(DrawerAction.EndSelection) },
            )
        else {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.conversations),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                FilledTonalButton(onClick = navigation.onNew) {
                    Icon(
                        painterResource(CoreDesignsystemR.drawable.ic_add),
                        null,
                        Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(CoreDesignsystemR.string.new_conversation),
                        Modifier.padding(start = 8.dp),
                    )
                }
            }
            Surface(
                onClick = navigation.onSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(
                        painterResource(CoreDesignsystemR.drawable.ic_search),
                        null,
                        Modifier.size(20.dp),
                    )
                    Text(
                        stringResource(CoreDesignsystemR.string.session_search),
                        Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    WorkspaceSwitcher(state.workspaces, onAction, navigation.onWorkspaces)
                }
                ViewOptions(
                    state,
                    drawerUi,
                    catalog.loading,
                    onAction,
                    onNewFolder = { dialog = FolderDialog.Create(emptyList()) },
                    onManageFolders = { dialog = FolderDialog.Manage },
                )
            }
            FilterBar(
                state,
                drawerUi,
                catalog.sessions,
                onFilter = { drawer(DrawerAction.Filter(it)) },
            )
        }
        if (catalog.failure != null && catalog.sessions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.drawer_load_failed),
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = { onAction(AppAction.RefreshSessions) },
                    enabled = !catalog.loading,
                ) {
                    Text(stringResource(R.string.drawer_retry))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.hostIdentity == "local" &&
                    state.engineControls.phase == "installing" &&
                    catalog.sessions.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.installing))
                        TextButton(onClick = navigation.onEngine) {
                            Text(stringResource(R.string.engine_install_view_progress))
                        }
                    }
                // A dropped link keeps the rows already read; only an empty list says disconnected.
                !state.connectionLive && !catalog.loading && catalog.sessions.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.drawer_disconnected),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        TextButton(onClick = navigation.onEngine) {
                            Text(stringResource(R.string.engine))
                        }
                    }
                blocks == null || catalog.loading ->
                    WhaleTail(
                        Modifier.align(Alignment.Center),
                        height = 32.dp,
                        contentDescription = stringResource(R.string.drawer_loading),
                    )
                (catalog.failure != null || state.error != null) && catalog.sessions.isEmpty() ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.drawer_load_failed),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        TextButton(onClick = { onAction(AppAction.RefreshSessions) }) {
                            Text(stringResource(R.string.drawer_retry))
                        }
                    }
                blocks.all { it.items.isEmpty() } &&
                    drawerUi.filter == DrawerFilter.All &&
                    drawerUi.view.folders.isEmpty() ->
                    EmptyLine(
                        stringResource(
                            if (state.workspaces.selectedId == null) R.string.drawer_empty
                            else R.string.drawer_empty_workspace,
                        ),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        for (block in blocks) {
                            when (block) {
                                is DrawerBlock.Filtered ->
                                    if (block.items.isEmpty())
                                        item("filter-empty") {
                                            EmptyLine(stringResource(R.string.drawer_filter_empty))
                                        }
                                is DrawerBlock.Pinned ->
                                    item("pinned") {
                                        Eyebrow(stringResource(R.string.drawer_pinned))
                                    }
                                is DrawerBlock.Folder ->
                                    item("folder-${block.folder.id}") {
                                        FolderHeader(
                                            block.folder,
                                            block.items.size,
                                            onToggle = {
                                                drawer(
                                                    DrawerAction.CollapseFolder(
                                                        block.folder.id,
                                                        !block.folder.collapsed,
                                                    ),
                                                )
                                            },
                                            onRename = { dialog = FolderDialog.Edit(block.folder) },
                                            onDelete = {
                                                val folder = block.folder
                                                drawer(DrawerAction.DeleteFolder(folder.id))
                                                notice =
                                                    DrawerNotice(
                                                        text = R.string.drawer_folder_deleted,
                                                        onUndo = {
                                                            drawer(
                                                                DrawerAction.CreateFolder(
                                                                    folder.name,
                                                                    folder.sessionIds,
                                                                    folder.tint,
                                                                    folder.emoji,
                                                                ),
                                                            )
                                                        },
                                                        onCommit = {},
                                                    )
                                            },
                                        )
                                    }
                                is DrawerBlock.Day ->
                                    item("day-${block.day.name}") {
                                        Eyebrow(stringResource(dayLabel(block.day)))
                                    }
                            }
                            val rows =
                                if (block is DrawerBlock.Folder && block.folder.collapsed)
                                    emptyList()
                                else block.items
                            items(rows, key = { "${blockKey(block)}/${it.sessionId}" }) { session ->
                                SessionRow(
                                    session,
                                    current = session.sessionId == current && !selection.active,
                                    pending = state.sessionAttention.pending[session.sessionId],
                                    unread = session.sessionId in state.sessionAttention.unread,
                                    indent = block is DrawerBlock.Folder,
                                    selecting = selection.active,
                                    selected = selection.ids?.contains(session.sessionId) == true,
                                    detailed = detailed,
                                    onClick = {
                                        if (selection.active)
                                            drawer(DrawerAction.ToggleSelection(session.sessionId))
                                        else navigation.onOpen(session.sessionId)
                                    },
                                    pinned =
                                        block is DrawerBlock.Folder &&
                                            session.sessionId in pinnedIds,
                                    onLongClick = {
                                        if (selection.active)
                                            drawer(DrawerAction.ToggleSelection(session.sessionId))
                                        else menuFor = session.sessionId
                                    },
                                    menu = {
                                        SessionMenu(
                                            expanded = menuFor == session.sessionId,
                                            session = session,
                                            state = state,
                                            drawerUi = drawerUi,
                                            onDismiss = { menuFor = null },
                                            onAction = onAction,
                                            onSelect = {
                                                drawer(
                                                    DrawerAction.StartSelection(session.sessionId),
                                                )
                                            },
                                            onArchive = { archive(listOf(session.sessionId)) },
                                            onNewFolder = {
                                                dialog =
                                                    FolderDialog.Create(listOf(session.sessionId))
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
            }
        }
        notice?.let { DrawerNoticeBar(it, onDone = { notice = null }) }
        HorizontalDivider()
        if (selection.active)
            SelectionBar(
                count = selection.ids.orEmpty().size,
                folders = drawerUi.view.folders,
                onMove = { folderId ->
                    drawer(DrawerAction.MoveSessions(selection.ids.orEmpty().toList(), folderId))
                    drawer(DrawerAction.EndSelection)
                },
                onNewFolder = { dialog = FolderDialog.Create(selection.ids.orEmpty().toList()) },
                onPin = {
                    val ids = selection.ids.orEmpty()
                    val unpin = ids.isNotEmpty() && ids.all { it in pinnedIds }
                    ids.forEach {
                        onAction(
                            AppAction.ManageWorkspaceSession(
                                it,
                                if (unpin) WorkspaceSessionOperation.Unpin
                                else WorkspaceSessionOperation.Pin,
                            ),
                        )
                    }
                    drawer(DrawerAction.EndSelection)
                },
                onArchive = {
                    archive(selection.ids.orEmpty().toList())
                    drawer(DrawerAction.EndSelection)
                },
                onDelete = {
                    drawer(DrawerAction.RequestDeletion(selection.ids.orEmpty().toList()))
                },
            )
        else IdentityBar(state, navigation, onAction)
    }
    when (val d = dialog) {
        is FolderDialog.Create ->
            FolderEditorDialog(
                R.string.drawer_folder_new,
                null,
                drawerUi.view.folders.map { it.name }.toSet(),
                onDismiss = { dialog = null },
            ) { name, tint, emoji ->
                drawer(DrawerAction.CreateFolder(name, d.ids, tint, emoji))
                drawer(DrawerAction.EndSelection)
            }
        is FolderDialog.Edit ->
            FolderEditorDialog(
                R.string.drawer_folder_rename,
                d.folder,
                drawerUi.view.folders.map { it.name }.toSet(),
                onDismiss = { dialog = null },
            ) { name, tint, emoji ->
                drawer(
                    DrawerAction.UpdateFolder(
                        d.folder.copy(name = name, tint = tint, emoji = emoji),
                    ),
                )
            }
        FolderDialog.Manage ->
            ManageFoldersDialog(
                drawerUi.view.folders,
                onAction = ::drawer,
                onEdit = { dialog = FolderDialog.Edit(it) },
                onDismiss = { dialog = null },
            )
        null -> Unit
    }
    DeletionDialogs(deletion, onAction)
}

private fun blockKey(block: DrawerBlock) =
    when (block) {
        is DrawerBlock.Pinned -> "pinned"
        is DrawerBlock.Folder -> "folder-${block.folder.id}"
        is DrawerBlock.Day -> "day-${block.day.name}"
        is DrawerBlock.Filtered -> "filtered"
    }

private fun dayLabel(day: SessionDay) =
    when (day) {
        SessionDay.Today -> R.string.drawer_day_today
        SessionDay.Yesterday -> R.string.drawer_day_yesterday
        SessionDay.ThisWeek -> R.string.drawer_day_week
        SessionDay.Earlier -> R.string.drawer_day_earlier
    }

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun FilterBar(
    state: AppState,
    drawerUi: DrawerUiState,
    sessions: List<SessionSummary>,
    onFilter: (DrawerFilter) -> Unit,
) {
    val pending = state.sessionAttention.pending.size
    val unread = state.sessionAttention.unread.size
    // A handful of idle conversations needs no filtering; keep the bar once a chip is in use.
    if (
        drawerUi.filter == DrawerFilter.All &&
            sessions.size <= 3 &&
            pending == 0 &&
            unread == 0 &&
            sessions.none { it.running }
    )
        return
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (filter in DrawerFilter.entries) {
            val label = stringResource(filterLabel(filter))
            FilterChip(
                selected = drawerUi.filter == filter,
                onClick = { onFilter(filter) },
                label = {
                    Text(
                        when {
                            filter == DrawerFilter.Pending && pending > 0 -> "$label $pending"
                            filter == DrawerFilter.Unread && unread > 0 -> "$label $unread"
                            else -> label
                        },
                    )
                },
            )
        }
    }
}

private fun filterLabel(filter: DrawerFilter) =
    when (filter) {
        DrawerFilter.All -> R.string.drawer_filter_all
        DrawerFilter.Running -> R.string.drawer_filter_running
        DrawerFilter.Pending -> R.string.drawer_filter_pending
        DrawerFilter.Unread -> R.string.drawer_filter_unread
    }

@Composable
private fun WorkspaceSwitcher(
    workspaces: WorkspacesUiState,
    onAction: (AppAction) -> Unit,
    onManage: () -> Unit,
) {
    if (!workspaces.connected) return
    var open by remember { mutableStateOf(false) }
    val selected = workspaces.rows.firstOrNull { it.id == workspaces.selectedId }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { open = true }) {
            Text(
                selected?.title?.ifBlank { selected.path }
                    ?: stringResource(CoreDesignsystemR.string.workspace_all),
                Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painterResource(CoreDesignsystemR.drawable.ic_chevron_right),
                null,
                Modifier.padding(start = 4.dp).size(18.dp).rotate(90f),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreDesignsystemR.string.workspace_all)) },
                onClick = {
                    open = false
                    onAction(AppAction.SelectWorkspace(null))
                },
            )
            for (row in workspaces.rows) DropdownMenuItem(
                text = { Text(row.title.ifBlank { row.path }) },
                onClick = {
                    open = false
                    onAction(AppAction.SelectWorkspace(row.id))
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(CoreDesignsystemR.string.workspaces)) },
                onClick = {
                    open = false
                    onManage()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(
    folder: DrawerFolder,
    count: Int,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val anchor = rememberContextMenuAnchor()
    Box(anchor.modifier) {
        ListItem(
            modifier =
                Modifier.padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.large)
                    .combinedClickable(
                        onClick = onToggle,
                        onLongClick = {
                            anchor.capture()
                            menu = true
                        },
                    ),
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(CoreDesignsystemR.drawable.ic_chevron_right),
                        null,
                        Modifier.size(20.dp).rotate(if (folder.collapsed) 0f else 90f),
                    )
                    FolderMark(folder.tint, folder.emoji)
                }
            },
            trailingContent = { Text("$count") },
        ) {
            Text(folder.name, maxLines = 1)
        }
        ContextMenuPopupAnchor(anchor) {
            ContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_folder_rename)) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_folder_delete)) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    current: Boolean,
    pending: PendingKind?,
    unread: Boolean,
    indent: Boolean,
    selecting: Boolean,
    selected: Boolean,
    pinned: Boolean,
    detailed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val highlighted = if (selecting) selected else current
    val anchor = rememberContextMenuAnchor()
    Box(anchor.modifier) {
        ListItem(
            modifier =
                Modifier.padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.large)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            anchor.capture()
                            onLongClick()
                        },
                    ),
            colors =
                ListItemDefaults.colors(
                    containerColor =
                        if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent,
                ),
            leadingContent =
                if (selecting) {
                    { Checkbox(selected, onCheckedChange = null) }
                } else if (indent) {
                    { Box(Modifier.size(20.dp)) }
                } else null,
            supportingContent =
                // On a wide list pane each row has room for the last-updated metadata that the
                // phone drawer keeps out of the way.
                if (detailed && session.updatedAt > 0) {
                    {
                        val locale =
                            androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                        Text(
                            stringResource(
                                R.string.drawer_updated_at,
                                remember(session.updatedAt, locale) {
                                    java.text.DateFormat.getDateTimeInstance(
                                            java.text.DateFormat.SHORT,
                                            java.text.DateFormat.SHORT,
                                            locale,
                                        )
                                        .format(java.util.Date(session.updatedAt))
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else null,
            trailingContent = {
                when {
                    pending != null ->
                        Text(
                            stringResource(
                                if (pending == PendingKind.Question)
                                    R.string.drawer_pending_question
                                else R.string.drawer_pending_approval,
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    session.running ->
                        WhaleTail(
                            height = 20.dp,
                            contentDescription = stringResource(R.string.tool_running),
                        )
                    session.pendingWrite ->
                        Text(
                            stringResource(R.string.session_pending_sync),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    pinned -> Text("📌", style = MaterialTheme.typography.labelSmall)
                    unread ->
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp),
                        ) {}
                }
            },
        ) {
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            val untitled = stringResource(CoreDesignsystemR.string.new_conversation)
            val fallbackTitle =
                remember(session.updatedAt, locale, untitled, detailed) {
                    // Untitled rows would otherwise be indistinguishable; tell them apart by time
                    // unless the metadata line already carries it.
                    if (session.updatedAt <= 0 || detailed) untitled
                    else
                        untitled +
                            " · " +
                            java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT,
                                    java.text.DateFormat.SHORT,
                                    locale,
                                )
                                .format(java.util.Date(session.updatedAt))
                }
            Text(
                session.title ?: fallbackTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ContextMenuPopupAnchor(anchor) { menu() }
    }
}

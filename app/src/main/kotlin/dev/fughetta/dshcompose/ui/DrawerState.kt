package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.sessions.DrawerUiState
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.collections.immutable.*

const val DRAFT_SESSION_ID = "new"

enum class DrawerFilter {
    All,
    Running,
    Pending,
    Unread,
}

enum class SessionDay {
    Today,
    Yesterday,
    ThisWeek,
    Earlier,
}

enum class PendingKind {
    Approval,
    Plan,
    Question,
}

data class DrawerSelection(val ids: PersistentSet<String>? = null) {
    val active
        get() = ids != null

    fun start(first: String? = null) = DrawerSelection(listOfNotNull(first).toPersistentSet())

    fun toggle(id: String): DrawerSelection =
        ids?.let { copy(ids = if (id in it) it.removing(id) else it.adding(id)) } ?: this

    fun selectAll(selected: Collection<String>) =
        if (active) copy(ids = selected.toPersistentSet()) else this

    fun end() = DrawerSelection()
}

fun sessionDayOf(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): SessionDay {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        day >= today -> SessionDay.Today
        day == today.minusDays(1) -> SessionDay.Yesterday
        day >= today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) -> SessionDay.ThisWeek
        else -> SessionDay.Earlier
    }
}

sealed interface DrawerBlock {
    val items: List<SessionSummary>

    data class Pinned(override val items: List<SessionSummary>) : DrawerBlock

    data class Folder(val folder: DrawerFolder, override val items: List<SessionSummary>) :
        DrawerBlock

    data class Day(val day: SessionDay, override val items: List<SessionSummary>) : DrawerBlock

    data class Filtered(val filter: DrawerFilter, override val items: List<SessionSummary>) :
        DrawerBlock
}

fun drawerBlocks(
    sessions: List<SessionSummary>,
    pinnedIds: List<String>,
    view: DrawerView,
    filter: DrawerFilter = DrawerFilter.All,
    pending: Map<String, PendingKind> = emptyMap(),
    unread: Set<String> = emptySet(),
    createdAt: Map<String, Long> = view.createdAt,
    now: Long = System.currentTimeMillis(),
    scoped: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DrawerBlock> {
    fun key(s: SessionSummary) =
        if (view.sort == DrawerSort.Created) createdAt[s.sessionId] ?: s.updatedAt else s.updatedAt
    val sorted =
        sessions.sortedWith(compareByDescending<SessionSummary>(::key).thenBy { it.sessionId })
    if (filter != DrawerFilter.All)
        return listOf(
            DrawerBlock.Filtered(
                filter,
                sorted.filter {
                    when (filter) {
                        DrawerFilter.Running -> it.running
                        DrawerFilter.Pending -> it.sessionId in pending
                        DrawerFilter.Unread -> it.sessionId in unread
                        DrawerFilter.All -> true
                    }
                },
            ),
        )
    val folderOf = view.folderOf
    val pins = pinnedIds.toSet()
    val byId = sorted.associateBy { it.sessionId }
    return buildList {
        val pinned = pinnedIds.distinct().filterNot(folderOf::containsKey).mapNotNull(byId::get)
        if (pinned.isNotEmpty()) add(DrawerBlock.Pinned(pinned))
        val members = sorted.filter { it.sessionId in folderOf }.groupBy { folderOf[it.sessionId] }
        for (folder in view.folders) {
            val rows = members[folder.id].orEmpty()
            if (scoped && rows.isEmpty()) continue
            add(
                DrawerBlock.Folder(
                    folder,
                    rows.filter { it.sessionId in pins } + rows.filterNot { it.sessionId in pins },
                ),
            )
        }
        val days =
            sorted
                .filterNot { it.sessionId in folderOf || it.sessionId in pins }
                .groupBy { sessionDayOf(key(it), now, zone) }
        for (day in SessionDay.entries) days[day]?.let { add(DrawerBlock.Day(day, it)) }
    }
}

fun drawerSelectableIds(blocks: List<DrawerBlock>): List<String> =
    blocks
        .filterNot { it is DrawerBlock.Folder && it.folder.collapsed }
        .flatMap { it.items.map(SessionSummary::sessionId) }

data class SessionAttentionState(
    val pending: PersistentMap<String, PendingKind> = persistentMapOf(),
    val unread: PersistentSet<String> = persistentSetOf(),
)

/** A child's request belongs to its top-level conversation. Strongest kind wins. */
fun sessionPendingInteractions(
    requests: List<Pair<String, PendingKind>>,
    sessions: List<SessionSummary>,
): PersistentMap<String, PendingKind> {
    val byId = sessions.associateBy { it.sessionId }
    val result = mutableMapOf<String, PendingKind>()
    for ((session, kind) in requests) {
        var root = session
        val visited = hashSetOf<String>()
        while (visited.add(root)) {
            val row = byId[root] ?: break
            if (row.origin != "subagent") break
            root = row.parentSessionId?.takeIf(String::isNotEmpty) ?: break
        }
        if (result[root] == null || kind.ordinal < result.getValue(root).ordinal)
            result[root] = kind
    }
    return result.toPersistentMap()
}

sealed interface DrawerAction {
    data class Filter(val filter: DrawerFilter) : DrawerAction

    data class Sort(val sort: DrawerSort) : DrawerAction

    data class CreateFolder(
        val name: String,
        val sessionIds: List<String> = emptyList(),
        val tint: Int? = null,
        val emoji: String? = null,
    ) : DrawerAction

    data class UpdateFolder(val folder: DrawerFolder) : DrawerAction

    data class DeleteFolder(val id: String) : DrawerAction

    data class MoveSessions(val ids: List<String>, val folderId: String?) : DrawerAction

    data class CollapseFolder(val id: String, val collapsed: Boolean) : DrawerAction

    data class ReorderFolder(val oldIndex: Int, val newIndex: Int) : DrawerAction

    data class StartSelection(val first: String? = null) : DrawerAction

    data class ToggleSelection(val id: String) : DrawerAction

    data class SelectAll(val ids: List<String>) : DrawerAction

    data object EndSelection : DrawerAction

    data class MarkSeen(val id: String) : DrawerAction

    data class RequestDeletion(val ids: List<String>) : DrawerAction

    data object ConfirmDeletion : DrawerAction

    data object DismissDeletion : DrawerAction
}

/** Projects the current workspace through host pins, local folders and status filters. */
fun AppState.drawerBlocks(
    catalog: SessionCatalogState,
    drawerUi: DrawerUiState,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<DrawerBlock>? {
    if (
        !drawerUi.loaded ||
            !workspaces.preferencesLoaded ||
            workspaces.loading && !workspaces.loaded ||
            catalog.loading
    )
        return null
    val workspace = workspaces.rows.firstOrNull { it.id == workspaces.selectedId }
    if (workspaces.selectedId != null && workspace == null) return null
    val members = workspace?.sessionIds
    val rows =
        catalog.sessions.filter {
            (members == null || it.sessionId in members) &&
                (it.sessionId in workspaces.archived) == workspaces.showArchived
        }
    return drawerBlocks(
        rows,
        workspaces.pinned,
        drawerUi.view,
        drawerUi.filter,
        if (connectionLive) sessionAttention.pending else emptyMap(),
        if (connectionLive) sessionAttention.unread else emptySet(),
        now = now,
        scoped = workspace != null,
        zone = zone,
    )
}

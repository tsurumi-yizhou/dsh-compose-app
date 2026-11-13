package dev.fughetta.dshcompose.domain.workspaces

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.collections.immutable.*

sealed interface WorkspaceEvent {
    data class Reset(val generation: Any?) : WorkspaceEvent

    data class Snapshot(
        val workspaces: PersistentList<Workspace>,
        val archived: PersistentSet<String>,
        val pinned: PersistentList<String>,
    ) : WorkspaceEvent

    data class Upsert(val workspace: Workspace) : WorkspaceEvent

    data class Remove(val id: String) : WorkspaceEvent

    data class Order(val ids: PersistentList<String>) : WorkspaceEvent

    data class Archived(val ids: PersistentSet<String>) : WorkspaceEvent

    data class Pinned(val ids: PersistentList<String>) : WorkspaceEvent
}

/** Stream updates take precedence over replies to mutations started before them. */
class WorkspaceRegistry {
    private val rows = linkedMapOf<String, Workspace>()
    private var order = persistentListOf<String>()
    private val changes = hashMapOf<String, Long>()
    private var revision = 0L
    private var replacement = 0L
    private var orderRevision = 0L
    private var archivedRevision = 0L
    private var pinnedRevision = 0L
    var archived: PersistentSet<String> = persistentSetOf()
        private set

    var pinned: PersistentList<String> = persistentListOf()
        private set

    class Receipt internal constructor(internal val revision: Long, internal val replacement: Long)

    fun beginMutation() = Receipt(revision, replacement)

    fun snapshot(): PersistentList<Workspace> {
        val ordered = order.toHashSet()
        return (order.mapNotNull(rows::get) + rows.values.filter { it.id !in ordered })
            .toPersistentList()
    }

    fun apply(update: WorkspaceEvent) {
        when (update) {
            is WorkspaceEvent.Reset -> {
                rows.clear()
                order = persistentListOf()
                changes.clear()
                replacement++
                archived = persistentSetOf()
                pinned = persistentListOf()
            }
            is WorkspaceEvent.Snapshot -> {
                rows.clear()
                update.workspaces.forEach { rows[it.id] = it }
                order = update.workspaces.map { it.id }.toPersistentList()
                archived = update.archived
                pinned = update.pinned
                changes.clear()
                replacement++
            }
            is WorkspaceEvent.Upsert -> {
                val newPosition = update.workspace.id !in order
                upsert(update.workspace)
                changes[update.workspace.id] = ++revision
                if (newPosition) orderRevision = revision
            }
            is WorkspaceEvent.Remove -> {
                rows.remove(update.id)
                order = order.removing(update.id)
                changes[update.id] = ++revision
                orderRevision = revision
            }
            is WorkspaceEvent.Order -> {
                order = update.ids
                orderRevision = ++revision
            }
            is WorkspaceEvent.Archived -> {
                archived = update.ids
                archivedRevision = ++revision
            }
            is WorkspaceEvent.Pinned -> {
                pinned = update.ids
                pinnedRevision = ++revision
            }
        }
    }

    fun acknowledge(workspace: Workspace, since: Receipt) {
        if (since.replacement != replacement || (changes[workspace.id] ?: 0) > since.revision)
            return
        upsert(workspace)
    }

    fun acknowledgeRemoval(id: String, since: Receipt) {
        if (since.replacement != replacement || (changes[id] ?: 0) > since.revision) return
        rows.remove(id)
        order = order.removing(id)
        changes[id] = ++revision
    }

    fun acknowledgeOrder(ids: PersistentList<String>, since: Receipt) {
        if (since.replacement != replacement || orderRevision > since.revision) return
        order = ids
        orderRevision = ++revision
    }

    fun acknowledgePinned(ids: PersistentList<String>, since: Receipt) {
        if (since.replacement != replacement || pinnedRevision > since.revision) return
        pinned = ids
        pinnedRevision = ++revision
    }

    fun acknowledgeArchived(ids: PersistentSet<String>, since: Receipt) {
        if (since.replacement != replacement || archivedRevision > since.revision) return
        archived = ids
        archivedRevision = ++revision
    }

    private fun upsert(row: Workspace) {
        rows[row.id] = row
        if (row.id !in order) order = order.addingAt(0, row.id)
    }
}

fun workspaceSessions(
    sessions: List<SessionSummary>,
    workspace: Workspace?,
    archived: Set<String>,
    showArchived: Boolean,
    pinned: List<String>,
): PersistentList<SessionSummary> {
    val pinSet = pinned.toHashSet()
    val pinnedRows = hashMapOf<String, SessionSummary>()
    val remaining = persistentListOf<SessionSummary>().builder()
    for (row in sessions) {
        if (
            (row.sessionId in archived) != showArchived ||
                workspace != null && row.sessionId !in workspace.sessionIds
        )
            continue
        if (row.sessionId in pinSet) pinnedRows[row.sessionId] = row else remaining.add(row)
    }
    return pinned.mapNotNull(pinnedRows::get).toPersistentList().addingAll(remaining)
}

package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.collections.immutable.*

/** Indexed list edits and a journal of acknowledged changes made during an inventory read. */
internal class SessionInventoryRead(val revision: Long)

internal class SessionInventory {
    private data class Change(val revision: Long, val value: SessionSummary?)

    private var values = persistentListOf<SessionSummary>()
    private var positions = hashMapOf<String, Int>()
    private val changes = linkedMapOf<String, Change>()
    private var revision = 0L

    @Synchronized fun snapshot(): PersistentList<SessionSummary> = values

    @Synchronized fun beginRead() = SessionInventoryRead(revision)

    @Synchronized fun get(id: String): SessionSummary? = positions[id]?.let(values::get)

    @Synchronized
    fun replace(
        rows: List<SessionSummary>,
        since: SessionInventoryRead,
    ): PersistentList<SessionSummary> {
        val pending = changes.filterValues { it.revision > since.revision }
        val seen = hashSetOf<String>()
        val merged = persistentListOf<SessionSummary>().builder()
        for (row in rows) {
            seen.add(row.sessionId)
            val change = pending[row.sessionId]
            val value = if (change == null) row else change.value
            if (value != null) merged.add(value)
        }
        val added =
            pending.values
                .mapNotNull { it.value }
                .filter { it.sessionId !in seen }
                .sortedByDescending { it.updatedAt }
        values = added.toPersistentList().addingAll(merged)
        reindex()
        changes.clear()
        return values
    }

    @Synchronized
    fun upsert(row: SessionSummary): PersistentList<SessionSummary> {
        val index = positions[row.sessionId]
        if (index == null) {
            values = values.addingAt(0, row)
            reindex()
        } else values = values.replacingAt(index, row)
        changes[row.sessionId] = Change(++revision, row)
        return values
    }

    @Synchronized
    fun renamed(id: String, title: String): PersistentList<SessionSummary> {
        positions[id]?.let { upsert(values[it].copy(title = title)) }
        return values
    }

    @Synchronized
    fun removed(id: String): PersistentList<SessionSummary> {
        positions[id]?.let {
            values = values.removingAt(it)
            reindex()
        }
        changes[id] = Change(++revision, null)
        return values
    }

    private fun reindex() {
        positions = HashMap(values.size)
        values.forEachIndexed { index, row -> positions[row.sessionId] = index }
    }
}

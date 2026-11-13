package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.messages.InboxItem
import dev.fughetta.dshcompose.domain.messages.InboxSlot
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

private val InboxSlot.wire: String
    get() =
        when (this) {
            InboxSlot.NextStep -> "next-step"
            InboxSlot.NextTurn -> "next-turn"
        }

private fun decodeInboxItem(value: JsonElement, slot: InboxSlot): InboxItem {
    val row = value.objectValue()
    val parts =
        row["content"] as? JsonArray ?: throw HostProtocolException("Missing queued content")
    val source = row["source"] as? JsonObject
    return InboxItem(
        row.requiredString("id"),
        slot,
        parts
            .mapNotNull {
                (it as? JsonObject)
                    ?.takeIf { part -> part.string("type") == "text" }
                    ?.string("text")
            }
            .joinToString("\n"),
        source?.string("rpcId"),
        source?.string("kind").orEmpty(),
        source?.string("form"),
        parts.all { (it as? JsonObject)?.string("type") == "text" },
    )
}

/** Snapshot-seeded inbox state; old history pages cannot re-enqueue previously consumed prompts. */
internal class InboxProjection {
    private var seed = persistentMapOf<InboxSlot, PersistentList<InboxItem>>()
    private var slots = seed
    private var asOfSeq = -1L
    var current: PersistentList<InboxItem> = persistentListOf()
        private set

    fun seed(projections: JsonObject) {
        asOfSeq = projections.long("asOfSeq") ?: -1
        val inbox = (projections["values"] as? JsonObject)?.get("inbox") as? JsonObject
        seed =
            InboxSlot.entries
                .associateWith { slot ->
                    (inbox?.get(slot.wire) as? JsonArray)
                        .orEmpty()
                        .map { decodeInboxItem(it, slot) }
                        .toPersistentList()
                }
                .toPersistentMap()
    }

    fun apply(record: SessionRecord) {
        if (record.seq <= asOfSeq || record.type != "agent/inbox/spliced") return
        val data = record.data
        val slot =
            InboxSlot.entries.firstOrNull { it.wire == data.string("target") }
                ?: throw HostProtocolException("Invalid inbox slot")
        val before = slots[slot] ?: persistentListOf()
        // The engine omits `start`, `removedCount` and `inserted` when they are zero or empty.
        val offset = (data["start"] as? JsonPrimitive)?.intOrNull ?: 0
        val start =
            if (offset < 0) (before.size + offset).coerceAtLeast(0)
            else offset.coerceAtMost(before.size)
        val removed = (data["removedCount"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
        val inserted = data["inserted"] as? JsonArray ?: JsonArray(emptyList())
        val after = before.builder()
        repeat(minOf(removed, before.size - start)) { after.removeAt(start) }
        after.addAll(start, inserted.map { decodeInboxItem(it, slot) })
        slots = slots.putting(slot, after.build())
        publish()
    }

    fun rebuild(records: Iterable<SessionRecord>) {
        slots = seed
        for (record in records) apply(record)
        publish()
    }

    private fun publish() {
        val next = InboxSlot.entries.flatMap { slots[it].orEmpty() }.toPersistentList()
        if (next != current) current = next
    }
}

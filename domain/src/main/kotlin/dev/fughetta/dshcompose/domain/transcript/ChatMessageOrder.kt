package dev.fughetta.dshcompose.domain.transcript

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.media.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

data class ChatFollowingMessage(
    val current: Boolean,
    val userText: String? = null,
    val laterAssistant: Boolean = false,
)

/**
 * Immutable message-order index, shared between snapshots; tool updates and token deltas add no
 *
 * history scan.
 */
class ChatMessageOrder
private constructor(
    private val users: PersistentList<Entry>,
    private val assistants: PersistentList<Entry>,
) {

    private data class Entry(val key: String, val seq: Long, val text: String? = null)

    fun adding(item: ChatItem): ChatMessageOrder {

        val seq = item.seq ?: return this

        return when {
            item.role == ChatRole.User ->
                ChatMessageOrder(insert(users, Entry(item.key, seq, item.text)), assistants)

            item.role == ChatRole.Assistant && item.text.isNotBlank() ->
                ChatMessageOrder(users, insert(assistants, Entry(item.key, seq)))

            else -> this
        }
    }

    fun following(item: ChatItem): ChatFollowingMessage {

        val seq = item.seq ?: return ChatFollowingMessage(false)

        var index = lowerBound(assistants, seq)

        while (
            index < assistants.size &&
                assistants[index].seq == seq &&
                assistants[index].key != item.key
        ) index++

        if (
            index >= assistants.size ||
                assistants[index].seq != seq ||
                assistants[index].key != item.key
        )
            return ChatFollowingMessage(false)

        return ChatFollowingMessage(
            true,
            users.getOrNull(upperBound(users, seq))?.text,
            index + 1 < assistants.size,
        )
    }

    /**
     * A tool card stays current through its own turn's later assistant text; only the next user
     *
     * message settles it.
     */
    fun followingTool(item: ChatItem): ChatFollowingMessage {

        val seq = item.seq ?: return ChatFollowingMessage(false)

        return ChatFollowingMessage(true, users.getOrNull(upperBound(users, seq))?.text)
    }

    companion object {

        val Empty = ChatMessageOrder(persistentListOf(), persistentListOf())

        private fun lowerBound(entries: List<Entry>, seq: Long): Int {

            var low = 0

            var high = entries.size

            while (low < high) {

                val mid = (low + high) ushr 1

                if (entries[mid].seq < seq) low = mid + 1 else high = mid
            }

            return low
        }

        private fun upperBound(entries: List<Entry>, seq: Long): Int {

            var low = 0

            var high = entries.size

            while (low < high) {

                val mid = (low + high) ushr 1

                if (entries[mid].seq <= seq) low = mid + 1 else high = mid
            }

            return low
        }

        private fun insert(entries: PersistentList<Entry>, entry: Entry): PersistentList<Entry> =
            if (entries.isEmpty() || entries.last().seq <= entry.seq) entries.adding(entry)
            else entries.addingAt(upperBound(entries, entry.seq), entry)
    }
}

data class ChatPresentation(
    val items: PersistentList<ChatItem>,
    val order: ChatMessageOrder,
    val mediaRevision: Long = 0,
    val generatedMedia: PersistentMap<String, MediaTranscriptItem> = persistentMapOf(),
    val generatedMediaPaths: PersistentSet<String> = persistentSetOf(),
    val turns: TranscriptTurnIndex = TranscriptTurnIndex.Empty,
)

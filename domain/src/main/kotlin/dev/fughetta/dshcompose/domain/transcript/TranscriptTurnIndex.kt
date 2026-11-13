package dev.fughetta.dshcompose.domain.transcript

import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifact
import dev.fughetta.dshcompose.domain.sessions.TranscriptTurnTrace
import kotlinx.collections.immutable.*

data class TranscriptSegment(
    val key: String,
    val traceId: String?,
    val rows: PersistentList<Int>,
    val finalAssistant: Int? = null,
    val cards: PersistentList<Int> = persistentListOf(),
    val tools: Int = 0,
    val messages: Int = 0,
    val thoughts: Int = 0,
    val folded: Boolean = false,
    val foldEligible: Boolean = false,
    val last: Boolean = false,
) {
    val collapsedSize
        get() = 1 + cards.size + (if (finalAssistant != null) 1 else 0) + (if (last) 1 else 0)

    val size
        get() =
            if (folded) collapsedSize
            else rows.size + (if (foldEligible) 1 else 0) + (if (last) 1 else 0)
}

/**
 * Persistent order-statistics tree. Appending or changing one segment shares every other branch.
 */
data class TranscriptNode(
    val ordinal: Int,
    val value: TranscriptSegment,
    val priority: Int,
    val left: TranscriptNode? = null,
    val right: TranscriptNode? = null,
) {
    val size: Int = (left?.size ?: 0) + value.size + (right?.size ?: 0)
    val blocks: Int = (left?.blocks ?: 0) + 1 + (right?.blocks ?: 0)
    val unfoldedSize: Int =
        (left?.unfoldedSize ?: 0) +
            value.rows.size +
            (if (value.last) 1 else 0) +
            (right?.unfoldedSize ?: 0)
}

fun transcriptPriority(ordinal: Int): Int {
    var x = ordinal + 0x6D2B79F5
    x = (x xor (x ushr 16)) * 0x45D9F3B
    x = (x xor (x ushr 16)) * 0x45D9F3B
    return x xor (x ushr 16)
}

fun appendTranscriptNode(root: TranscriptNode?, node: TranscriptNode): TranscriptNode {
    if (root == null) return node
    if (node.priority > root.priority) {
        fun split(tree: TranscriptNode?): Pair<TranscriptNode?, TranscriptNode?> {
            if (tree == null) return null to null
            return if (tree.ordinal < node.ordinal) {
                val (a, b) = split(tree.right)
                tree.copy(right = a) to b
            } else {
                val (a, b) = split(tree.left)
                a to tree.copy(left = b)
            }
        }
        val (a, b) = split(root)
        return node.copy(left = a, right = b)
    }
    return if (node.ordinal < root.ordinal) root.copy(left = appendTranscriptNode(root.left, node))
    else root.copy(right = appendTranscriptNode(root.right, node))
}

fun replaceTranscriptNode(
    root: TranscriptNode?,
    ordinal: Int,
    value: TranscriptSegment,
): TranscriptNode? {
    root ?: return null
    return when {
        ordinal < root.ordinal -> root.copy(left = replaceTranscriptNode(root.left, ordinal, value))
        ordinal > root.ordinal ->
            root.copy(right = replaceTranscriptNode(root.right, ordinal, value))
        else -> root.copy(value = value)
    }
}

fun transcriptPrefix(root: TranscriptNode?, before: Int, unfolded: Boolean = false): Int {
    root ?: return 0
    return if (root.ordinal >= before) transcriptPrefix(root.left, before, unfolded)
    else
        (if (unfolded) root.left?.unfoldedSize ?: 0 else root.left?.size ?: 0) +
            (if (unfolded) root.value.rows.size + (if (root.value.last) 1 else 0)
            else root.value.size) +
            transcriptPrefix(root.right, before, unfolded)
}

fun transcriptSegment(root: TranscriptNode?, ordinal: Int): TranscriptSegment? {
    root ?: return null
    return when {
        ordinal < root.ordinal -> transcriptSegment(root.left, ordinal)
        ordinal > root.ordinal -> transcriptSegment(root.right, ordinal)
        else -> root.value
    }
}

data class TranscriptRowLocation(val rawIndex: Int, val ordinal: Int)

class TranscriptTurnIndex
constructor(
    val root: TranscriptNode?,
    val traces: PersistentMap<String, TranscriptTurnTrace>,
    val ordinals: PersistentMap<String, Int>,
    val openOrdinal: Int?,
    val rawCount: Int,
    val artifacts: PersistentList<TranscriptArtifact> = persistentListOf(),
    val artifactPositions: PersistentMap<String, Int> = persistentMapOf(),
    val activeTurnId: String? = null,
    val rawRows: PersistentMap<String, TranscriptRowLocation> = persistentMapOf(),
) {
    fun rawPosition(key: String): Int? = rawRows[key]?.rawIndex

    val current
        get() = activeTurnId?.let(traces::get)

    fun trace(turnId: String?) = traces[turnId]

    companion object {
        val Empty = TranscriptTurnIndex(null, persistentMapOf(), persistentMapOf(), null, 0)
    }
}

sealed interface TranscriptPlannedRow {
    data class Item(val rawIndex: Int, val cardOnly: Boolean = false) : TranscriptPlannedRow

    data class Process(val segment: TranscriptSegment, val trace: TranscriptTurnTrace) :
        TranscriptPlannedRow

    data class Footnote(val trace: TranscriptTurnTrace) : TranscriptPlannedRow
}

/**
 * Build once per durable index/expansion change; token updates only change the items read by
 * rawIndex.
 */
class TranscriptRowPlan(
    index: TranscriptTurnIndex,
    itemsCount: Int,
    expanded: Set<String> = emptySet(),
    private val foldTurns: Boolean = true,
) {
    private var root = index.root
    private val traces = index.traces
    private val rawRows = index.rawRows
    private val ordinals = index.ordinals
    private val open = index.openOrdinal
    private val rawTail =
        if (open != null) transcriptSegment(index.root, open)?.rows?.firstOrNull() ?: index.rawCount
        else index.rawCount
    private val prefixSize: Int
    val count: Int

    init {
        if (foldTurns)
            expanded.forEach { key ->
                index.ordinals[key]?.let { ordinal ->
                    transcriptSegment(root, ordinal)?.let {
                        root = replaceTranscriptNode(root, ordinal, it.copy(folded = false))
                    }
                }
            }
        prefixSize =
            if (open == null) (if (foldTurns) root?.size ?: 0 else root?.unfoldedSize ?: 0)
            else transcriptPrefix(root, open, !foldTurns)
        count = prefixSize + (itemsCount - rawTail).coerceAtLeast(0)
    }

    fun positionOfKey(key: String): Int? {
        val direct = ordinals[key]
        val location = rawRows[key]
        val ordinal = direct ?: location?.ordinal ?: return null
        if (open != null && ordinal >= open) return null
        val value = transcriptSegment(root, ordinal) ?: return null
        val before = transcriptPrefix(root, ordinal, !foldTurns)
        if (direct != null)
            return if (key.startsWith("footnote:"))
                before +
                    (if (foldTurns) value.size else value.rows.size + (if (value.last) 1 else 0)) -
                    1
            else before
        val raw = location!!.rawIndex
        if (!foldTurns || !value.folded) {
            var low = 0
            var high = value.rows.size
            while (low < high) {
                val middle = (low + high).ushr(1)
                if (value.rows[middle] < raw) low = middle + 1 else high = middle
            }
            return before + low + (if (foldTurns && value.foldEligible) 1 else 0)
        }
        if (raw == value.finalAssistant) return before + 1 + value.cards.size
        val card = value.cards.binarySearch(raw)
        return if (card >= 0) before + 1 + card else before
    }

    fun rowAt(position: Int): TranscriptPlannedRow {
        require(position in 0 until count)
        if (root == null) return TranscriptPlannedRow.Item(position)
        if (position >= prefixSize)
            return TranscriptPlannedRow.Item(rawTail + position - prefixSize)
        var node = root
        var offset = position
        while (node != null) {
            val left = if (foldTurns) node.left?.size ?: 0 else node.left?.unfoldedSize ?: 0
            if (offset < left) {
                node = node.left
                continue
            }
            offset -= left
            val valueSize =
                if (foldTurns) node.value.size
                else node.value.rows.size + (if (node.value.last) 1 else 0)
            if (offset >= valueSize) {
                offset -= valueSize
                node = node.right
                continue
            }
            val value = node.value
            val trace = value.traceId?.let(traces::get)
            if (!foldTurns)
                return if (offset < value.rows.size) TranscriptPlannedRow.Item(value.rows[offset])
                else TranscriptPlannedRow.Footnote(trace!!)
            if (!value.folded) {
                if (value.foldEligible) {
                    if (offset == 0) return TranscriptPlannedRow.Process(value, trace!!)
                    offset--
                }
                return if (offset < value.rows.size) TranscriptPlannedRow.Item(value.rows[offset])
                else TranscriptPlannedRow.Footnote(trace!!)
            }
            if (offset == 0) return TranscriptPlannedRow.Process(value, trace!!)
            offset--
            if (offset < value.cards.size)
                return TranscriptPlannedRow.Item(value.cards[offset], true)
            if (value.finalAssistant != null && offset == value.cards.size)
                return TranscriptPlannedRow.Item(value.finalAssistant)
            return TranscriptPlannedRow.Footnote(trace!!)
        }
        error("Transcript row index")
    }
}

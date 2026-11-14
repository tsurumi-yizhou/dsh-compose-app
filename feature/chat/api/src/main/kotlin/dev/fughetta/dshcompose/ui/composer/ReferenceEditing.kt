package dev.fughetta.dshcompose.ui.composer

import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.sessions.search.unicodeRegex
import kotlinx.collections.immutable.*

enum class ReferenceSpanKind {
    File,
    Directory,
    Conversation,
}

data class ReferenceSpan(
    val start: Int,
    val end: Int,
    val label: String,
    val kind: ReferenceSpanKind,
)

data class ReferenceInput(val text: String, val start: Int, val end: Int) {
    val minimum
        get() = minOf(start, end)

    val maximum
        get() = maxOf(start, end)
}

data class ReferenceEdit(
    val input: ReferenceInput,
    val spans: PersistentList<ReferenceSpan>,
    val widened: Boolean,
)

data class ReferenceInsertion(val text: String, val label: String, val kind: ReferenceSpanKind)

data class ReferenceDisplaySpan(val source: ReferenceSpan, val start: Int, val end: Int)

data class ReferenceDisplay(val text: String, val spans: List<ReferenceDisplaySpan>) {
    fun originalOffset(offset: Int): Int {
        var delta = 0
        for (span in spans) {
            if (offset < span.source.start) return offset + delta
            if (offset <= span.source.end)
                return if (offset == span.source.start) span.start else span.end
            delta = span.end - span.source.end
        }
        return offset + delta
    }

    fun displayOffset(offset: Int): Int {
        var delta = 0
        for (span in spans) {
            if (offset < span.start) return offset + delta
            if (offset <= span.end)
                return if ((offset - span.start) * 2 < span.end - span.start) span.source.start
                else span.source.end
            delta = span.source.end - span.end
        }
        return offset + delta
    }
}

fun referenceDisplay(text: String, references: List<ReferenceSpan>): ReferenceDisplay {
    val result = StringBuilder()
    val spans = mutableListOf<ReferenceDisplaySpan>()
    var previous = 0
    for (reference in references) {
        result.append(text, previous, reference.start)
        val start = result.length
        result
            .append(" @")
            .append(reference.label.replace('\n', ' ').replace('\r', ' '))
            .append(' ')
        spans.add(ReferenceDisplaySpan(reference, start, result.length))
        previous = reference.end
    }
    result.append(text, previous, text.length)
    return ReferenceDisplay(result.toString(), spans)
}

fun insertReferenceDraft(
    before: String,
    spans: PersistentList<ReferenceSpan>,
    start: Int,
    end: Int,
    insertion: ReferenceInsertion,
): ReferenceEdit {
    val followingSpace = end < before.length && before[end].isWhitespace()
    val inserted = insertion.text + if (followingSpace) "" else " "
    val text = before.replaceRange(start, end, inserted)
    val delta = inserted.length - (end - start)
    val kept = spans.mapNotNull { span ->
        when {
            span.end <= start -> span
            span.start >= end -> span.copy(start = span.start + delta, end = span.end + delta)
            else -> null
        }
    }
    val next =
        (kept +
                ReferenceSpan(
                    start,
                    start + insertion.text.length,
                    insertion.label,
                    insertion.kind,
                ))
            .sortedBy { it.start }
            .toPersistentList()
    val caret = start + inserted.length + if (followingSpace) 1 else 0
    return ReferenceEdit(ReferenceInput(text, caret, caret), next, false)
}

private val sessionToken = Regex("""@\[((?:\\.|[^\\\]\n])*)]\(dsh-session:[A-Za-z0-9_-]+\)""")
private val pathToken = unicodeRegex("""(?<!\S)(@"[^"\n]+"|@"[^"\n]*/(?=\s|$)|@[^\s"]+)""")
private val pathLike = Regex("""^[^.]+\.[A-Za-z0-9]|^\.[A-Za-z0-9]""")
private val punctuation = Regex("""[.,;:!?，。；：！？、）)]+$""")

fun parseDraftReferences(text: String): PersistentList<ReferenceSpan> =
    parseMessageReferences(text, ReferenceLabelIndex.Empty)

fun parseMessageReferences(
    text: String,
    labels: ReferenceLabelIndex,
): PersistentList<ReferenceSpan> {
    if ('@' !in text) return persistentListOf()
    val spans = java.util.TreeMap<Int, ReferenceSpan>()
    fun overlaps(start: Int, end: Int): Boolean =
        (spans.floorEntry(start)?.value?.end ?: 0) > start ||
            (spans.ceilingKey(start) ?: Int.MAX_VALUE) < end
    for (match in sessionToken.findAll(text)) {
        val start = match.range.first
        spans[start] =
            ReferenceSpan(
                start,
                match.range.last + 1,
                match.groupValues[1].replace(Regex("""\\(.)"""), "$1"),
                ReferenceSpanKind.Conversation,
            )
    }
    var offset = 0
    while (labels !== ReferenceLabelIndex.Empty && offset < text.length) {
        if (text[offset] == '@' && (offset == 0 || text[offset - 1].isWhitespace())) {
            val end = labels.matchEnd(text, offset + 1)
            if (end != null && !overlaps(offset, end)) {
                spans[offset] =
                    ReferenceSpan(
                        offset,
                        end,
                        text.substring(offset + 1, end),
                        ReferenceSpanKind.Conversation,
                    )
                offset = end
                continue
            }
        }
        offset++
    }
    for (match in pathToken.findAll(text)) {
        val raw =
            if (match.value.startsWith("@\"")) match.value else match.value.replace(punctuation, "")
        val quoted = raw.startsWith("@\"")
        val path =
            raw.substring(if (quoted) 2 else 1).let {
                if (quoted && it.endsWith('"')) it.dropLast(1) else it
            }
        if (
            path.isEmpty() ||
                !quoted && '/' !in path && !path.startsWith('~') && !pathLike.containsMatchIn(path)
        )
            continue
        val start = match.range.first
        val end = start + raw.length
        if (overlaps(start, end)) continue
        val directory = path.endsWith('/')
        val name = path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path
        spans[start] =
            ReferenceSpan(
                start,
                end,
                if (directory) "$name/" else name,
                if (directory) ReferenceSpanKind.Directory else ReferenceSpanKind.File,
            )
    }
    return spans.values.toPersistentList()
}

/** Keep native IME edits in raw wire text while treating committed references as atomic spans. */
fun editReferenceDraft(
    before: ReferenceInput,
    after: ReferenceInput,
    spans: PersistentList<ReferenceSpan>,
    composing: Boolean,
): ReferenceEdit {
    if (before.text == after.text)
        return ReferenceEdit(snapReferenceSelection(before, after, spans), spans, false)
    val shorter = minOf(before.text.length, after.text.length)
    var start = 0
    while (start < shorter && before.text[start] == after.text[start]) start++
    start = minOf(start, before.minimum, after.minimum).coerceAtLeast(0)
    var suffix = 0
    while (
        suffix < shorter - start &&
            before.text[before.text.lastIndex - suffix] == after.text[after.text.lastIndex - suffix]
    ) suffix++
    var end = before.text.length - suffix
    val inserted = after.text.substring(start, after.text.length - suffix)
    var widened = false
    for (span in spans) {
        if (start == end) {
            if (start > span.start && start < span.end) {
                start = span.end
                end = span.end
                widened = true
            }
        } else if (span.start < end && start < span.end && (span.start < start || span.end > end)) {
            val inside = minOf(end, span.end) - maxOf(start, span.start)
            if (inside > span.end - span.start - inside) continue
            start = minOf(start, span.start)
            end = maxOf(end, span.end)
            widened = true
        }
    }
    val text = if (widened) before.text.replaceRange(start, end, inserted) else after.text
    val delta = inserted.length - (end - start)
    val kept = spans.mapNotNull { span ->
        when {
            span.end <= start -> span
            span.start >= end -> span.copy(start = span.start + delta, end = span.end + delta)
            else -> null
        }
    }
    val added =
        if (inserted.length > 1 && !composing)
            parseDraftReferences(text).filter {
                it.start >= start && it.end <= start + inserted.length
            }
        else emptyList()
    val next = (kept + added).sortedBy { it.start }.toPersistentList()
    val selection =
        if (widened) ReferenceInput(text, start + inserted.length, start + inserted.length)
        else after
    return ReferenceEdit(snapReferenceSelection(before, selection, next), next, widened)
}

private fun snapReferenceSelection(
    before: ReferenceInput,
    after: ReferenceInput,
    spans: List<ReferenceSpan>,
): ReferenceInput {
    fun snap(offset: Int, forward: Boolean): Int =
        spans
            .firstOrNull { offset > it.start && offset < it.end }
            ?.let { if (forward) it.end else it.start } ?: offset
    if (after.start == after.end) {
        val offset = snap(after.start, after.start >= before.start)
        return after.copy(start = offset, end = offset)
    }
    val start = snap(after.minimum, false)
    val end = snap(after.maximum, true)
    return if (after.start <= after.end) after.copy(start = start, end = end)
    else after.copy(start = end, end = start)
}

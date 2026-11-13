package dev.fughetta.dshcompose.ui.composer

import dev.fughetta.dshcompose.domain.skills.validSkillName
import kotlinx.collections.immutable.*

/**
 * Insert a whitespace-bounded skill gesture without replacing selected text or splitting
 * references.
 */
fun insertComposerSkill(
    text: String,
    selectionEnd: Int?,
    name: String,
    spans: PersistentList<ReferenceSpan> = persistentListOf(),
): ReferenceEdit {
    require(validSkillName(name))
    val token = "/$name"
    var from = 0
    while (from <= text.length) {
        val found = text.indexOf(token, from)
        if (found < 0) break
        val end = found + token.length
        if (
            (found == 0 || text[found - 1].isWhitespace()) &&
                (end == text.length || text[end].isWhitespace())
        ) {
            val cursor = end + if (end < text.length) 1 else 0
            return ReferenceEdit(ReferenceInput(text, cursor, cursor), spans, false)
        }
        from = found + 1
    }
    var at = (selectionEnd ?: text.length).coerceIn(0, text.length)
    if (at > 0 && at < text.length && text[at].isLowSurrogate() && text[at - 1].isHighSurrogate())
        at++
    spans.firstOrNull { at > it.start && at < it.end }?.let { at = it.end }
    val prefix = if (at > 0 && !text[at - 1].isWhitespace()) " " else ""
    val spaceAfter = at < text.length && text[at].isWhitespace()
    val insertion = "$prefix$token" + if (spaceAfter) "" else " "
    val cursor = at + insertion.length + if (spaceAfter) 1 else 0
    val shifted =
        spans
            .map { span ->
                if (span.start >= at)
                    span.copy(
                        start = span.start + insertion.length,
                        end = span.end + insertion.length,
                    )
                else span
            }
            .toPersistentList()
    return ReferenceEdit(
        ReferenceInput(text.substring(0, at) + insertion + text.substring(at), cursor, cursor),
        shifted,
        false,
    )
}

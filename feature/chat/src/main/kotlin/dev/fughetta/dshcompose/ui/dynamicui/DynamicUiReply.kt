// Portions derived from DS Bot (https://github.com/Fei2411/DeepSeek-Bot), Apache-2.0,

// Copyright 2026 Fei2411 and the DS Bot contributors. See THIRD_PARTY_NOTICES.md.

// Modified: Kotlin selection rules and replies built only from visible labels, with indexed message

// order.

package dev.fughetta.dshcompose.ui.dynamicui

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.transcript.ChatMessageOrder
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.markdown.MarkdownBlock
import dev.fughetta.dshcompose.ui.markdown.MarkdownDocuments
import dev.fughetta.dshcompose.ui.toUiJson
import kotlinx.serialization.json.JsonObject

internal const val UiReplyMarker = "[dsh-ui reply]"

internal enum class UiReplyKind {

    Choice,
    Confirm,
    Cancel,
    Other,
}

internal data class UiReply(
    val kind: UiReplyKind,
    val title: String? = null,
    val value: String? = null,
    val freetext: String? = null,
)

data class UiReplyLabels(
    val select: String,
    val other: String,
    val confirm: String,
    val cancel: String,
)

internal enum class UiInteraction {

    ReadOnly,
    Live,
    Running,
    Answered,
    Expired,
}

internal data class UiInteractionState(val mode: UiInteraction, val reply: UiReply? = null)

internal data class UiAnswer(
    val path: String,
    val selected: Set<Int> = emptySet(),
    val freetext: String? = null,
    val confirmed: Boolean? = null,
)

private fun flat(value: String?): String? =
    value?.replace(Regex("""\s*[\r\n]+\s*"""), " ")?.trim()?.takeIf { it.isNotEmpty() }

internal fun uiReplyText(reply: UiReply, labels: UiReplyLabels): String {

    val header = UiReplyMarker + (flat(reply.title)?.let { " $it" } ?: "")

    val body =
        when (reply.kind) {
            UiReplyKind.Choice ->
                listOfNotNull(
                        flat(reply.value)?.let { "${labels.select}: $it" },
                        flat(reply.freetext)?.let { "${labels.other}: $it" },
                    )
                    .joinToString("\n")

            UiReplyKind.Confirm ->
                labels.confirm +
                    (flat(reply.value)?.takeUnless { it == labels.confirm }?.let { ": $it" } ?: "")

            UiReplyKind.Cancel ->
                labels.cancel +
                    (flat(reply.value)?.takeUnless { it == labels.cancel }?.let { ": $it" } ?: "")

            UiReplyKind.Other -> flat(reply.value).orEmpty()
        }

    return header + if (body.isEmpty()) "" else "\n$body"
}

private val replyLine = Regex("""^(选择|确认|取消|Select|Confirm|Cancel)\s*(?:[：:]\s*(.*))?$""")

private val otherLine = Regex("""^(?:其他|Other)\s*[：:]\s*(.*)$""")

internal fun parseUiReply(text: String): UiReply? {

    val lines = text.trimStart().lines()

    val head = lines.firstOrNull()?.trim().orEmpty()

    if (!head.startsWith(UiReplyMarker)) return null

    val title = flat(head.substring(UiReplyMarker.length))

    val body = lines.drop(1).map(String::trim).filter(String::isNotBlank)

    val first = body.firstOrNull() ?: return UiReply(UiReplyKind.Other, title)

    otherLine.matchEntire(first)?.let {
        return UiReply(UiReplyKind.Choice, title, freetext = flat(it.groupValues[1]))
    }

    val match = replyLine.matchEntire(first) ?: return UiReply(UiReplyKind.Other, title, first)

    val kind =
        when (match.groupValues[1]) {
            "选择",
            "Select" -> UiReplyKind.Choice

            "确认",
            "Confirm" -> UiReplyKind.Confirm

            else -> UiReplyKind.Cancel
        }

    return UiReply(
        kind,
        title,
        flat(match.groupValues[2]),
        if (kind == UiReplyKind.Choice)
            body.getOrNull(1)?.let { otherLine.matchEntire(it)?.groupValues?.get(1) }?.let(::flat)
        else null,
    )
}

internal fun uiInteraction(
    row: ChatItem,
    order: ChatMessageOrder,
    running: Boolean,
): UiInteractionState {

    val following = if (row.tool != null) order.followingTool(row) else order.following(row)

    if (!following.current) return UiInteractionState(UiInteraction.Expired)

    following.userText?.let { user ->
        if (user.trimStart().startsWith(GenuiActionMarker))
            return UiInteractionState(UiInteraction.Answered)

        return parseUiReply(user)?.let { UiInteractionState(UiInteraction.Answered, it) }
            ?: UiInteractionState(UiInteraction.Expired)
    }

    return UiInteractionState(
        when {
            following.laterAssistant -> UiInteraction.Expired

            running || row.streaming -> UiInteraction.Running

            else -> UiInteraction.Live
        },
    )
}

internal fun UiNode.walk(): Sequence<UiNode> = sequence {
    yield(this@walk)

    if (this@walk is UiLayout) for (child in children) yieldAll(child.walk())

    if (this@walk is UiPanel) for (child in children) yieldAll(child.walk())

    if (this@walk is UiGrid) for (child in children) yieldAll(child.walk())

    if (this@walk is UiBrowse)
        for (page in pages) for (child in page.children) yieldAll(child.walk())
}

internal fun answerFor(view: UiView, reply: UiReply): UiAnswer? {

    if (reply.title != null && view.title != null && flat(view.title) != reply.title) return null

    for (node in view.root.walk()) when {
        reply.kind == UiReplyKind.Choice && node is UiOptions -> {

            val padded = "、${reply.value.orEmpty()}、"

            val selected =
                node.options.indices
                    .filter { padded.contains("、${flat(node.options[it].label)}、") }
                    .let { if (node.multi) it else it.take(1) }
                    .toSet()

            val typed = reply.freetext?.takeIf { node.freetext }

            if (selected.isNotEmpty() || !typed.isNullOrBlank())
                return UiAnswer(node.path, selected, typed)
        }

        node is UiConfirm && reply.kind in setOf(UiReplyKind.Confirm, UiReplyKind.Cancel) ->
            return UiAnswer(node.path, confirmed = reply.kind == UiReplyKind.Confirm)
    }

    return null
}

/**
 * Reconstruct the request from the current view; ids and hidden values never enter a user message.
 */
internal fun selectedReply(
    view: UiView,
    path: String,
    selected: List<Int>,
    freetext: String?,
    confirmed: Boolean?,
): UiReply? {

    val node = view.root.walk().firstOrNull { it.path == path } ?: return null

    return when (node) {
        is UiOptions -> {

            if (
                confirmed != null ||
                    selected.distinct().size != selected.size ||
                    selected.any { it !in node.options.indices } ||
                    !node.multi && selected.size > 1
            )
                return null

            val typed = flat(freetext)

            if (
                typed != null && (!node.freetext || typed.length > 2000) ||
                    selected.isEmpty() && typed == null
            )
                return null

            UiReply(
                UiReplyKind.Choice,
                flat(view.title),
                selected
                    .sorted()
                    .map { flat(node.options[it].label) }
                    .filterNotNull()
                    .joinToString("、")
                    .takeIf { it.isNotEmpty() },
                typed,
            )
        }

        is UiConfirm ->
            if (confirmed == null || selected.isNotEmpty() || !freetext.isNullOrBlank()) null
            else
                UiReply(
                    if (confirmed) UiReplyKind.Confirm else UiReplyKind.Cancel,
                    flat(view.title),
                    flat(if (confirmed) node.confirm else node.cancel),
                )

        else -> null
    }
}

fun dynamicUiCanAnswer(row: ChatItem, order: ChatMessageOrder, running: Boolean): Boolean =
    uiInteraction(row, order, running).mode == UiInteraction.Live

fun dynamicUiReplyText(
    row: ChatItem,
    source: String,
    path: String,
    selected: List<Int>,
    freetext: String?,
    confirmed: Boolean?,
    labels: UiReplyLabels,
): String? {

    if (source.length > DynamicUiParser.MaxBytes) return null

    val card = currentCard(row, source) ?: return null

    val view = card.parsed?.view ?: return null

    if (view.dialect != null) return null

    val reply = selectedReply(view, path, selected, freetext, confirmed) ?: return null

    return uiReplyText(reply, labels)
}

/** A `render_ui` result meta: the processed genui spec, persisted but never shown to the model. */
private fun genuiToolMeta(row: ChatItem): JsonObject? {

    val tool = row.tool ?: return null

    if (tool.name != "render_ui" || tool.state != ToolState.Succeeded) return null

    val meta = tool.meta?.toUiJson() as? JsonObject ?: return null

    return meta.takeUnless { it.containsKey("dsh-compose/view") }
}

private const val GenuiToolMaxBytes = 32768

/** The source a `render_ui` tool card answers from. */
fun genuiToolSource(row: ChatItem): String? =
    genuiToolMeta(row)?.toString()?.takeIf { it.length <= GenuiToolMaxBytes }

internal fun genuiToolView(row: ChatItem): UiParse? =
    genuiToolMeta(row)
        ?.takeIf { it.toString().length <= GenuiToolMaxBytes }
        ?.let { GenuiReader().parse(it) }

/**
 * The `[genui-action]` message for one interaction, rebuilt from the current source; null when the
 *
 * source changed, cannot produce it, or the click is graded locally.
 */
fun dynamicUiGenuiActionText(
    row: ChatItem,
    source: String,
    input: GenuiInput,
    format: (String, String) -> String,
): String? {

    val view =
        if (row.tool != null) {

            if (genuiToolSource(row) != source) return null

            genuiToolView(row)?.view
        } else {

            if (source.length > DynamicUiParser.MaxBytes) return null

            currentCard(row, source)?.parsed?.view
        } ?: return null

    return genuiActionText(view, input, format)
}

fun dynamicUiResendText(
    row: ChatItem,
    source: String,
    resolve: (Int) -> String,
    format: (String) -> String,
): String? {

    val card = currentCard(row, source) ?: return null

    val parsed = card.parsed ?: return null

    if (parsed.view != null || parsed.dialect != null) return null

    val explanation =
        parsed.issues.take(6).joinToString("; ") {
            "${it.path}: ${resolve(it.kind.labelResource())}"
        }

    return explanation.takeIf { it.isNotBlank() }?.let(format)
}

private fun currentCard(row: ChatItem, source: String): MarkdownBlock.Visual? {

    if (!row.text.contains(source.trimEnd())) return null

    return visualCards(MarkdownDocuments.parse(row.text, ReferenceLabelIndex.Empty).blocks)
        .firstOrNull { it.closed && it.source.trimEnd() == source.trimEnd() }
}

internal fun UiIssueKind.labelResource(): Int =
    when (this) {
        UiIssueKind.Json -> R.string.view_issue_json

        UiIssueKind.Size -> R.string.view_issue_size

        UiIssueKind.Depth -> R.string.view_issue_depth

        UiIssueKind.Required -> R.string.view_issue_required

        UiIssueKind.Unknown -> R.string.view_issue_unknown

        UiIssueKind.Truncated -> R.string.view_issue_truncated

        UiIssueKind.Value -> R.string.view_issue_value

        UiIssueKind.WebOnly -> R.string.view_issue_web
    }

private fun visualCards(blocks: List<MarkdownBlock>): Sequence<MarkdownBlock.Visual> = sequence {
    for (block in blocks) when (block) {
        is MarkdownBlock.Visual -> yield(block)

        is MarkdownBlock.Disclosure -> yieldAll(visualCards(block.blocks))

        is MarkdownBlock.Quote -> yieldAll(visualCards(block.blocks))

        is MarkdownBlock.ListEntry -> yieldAll(visualCards(block.blocks))

        else -> Unit
    }
}

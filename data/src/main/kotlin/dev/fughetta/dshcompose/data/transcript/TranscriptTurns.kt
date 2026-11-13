package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.sessions.TranscriptSpan
import dev.fughetta.dshcompose.domain.sessions.TranscriptTokenUsage
import dev.fughetta.dshcompose.domain.sessions.TranscriptTurnTrace
import dev.fughetta.dshcompose.domain.transcript.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

private fun decodeTranscriptTokenUsage(raw: JsonElement?): TranscriptTokenUsage {
    val row = raw as? JsonObject ?: return TranscriptTokenUsage()
    return TranscriptTokenUsage(
        row.long("inputTokens") ?: 0,
        row.long("cacheReadTokens") ?: 0,
        row.long("cacheWriteTokens") ?: 0,
        row.long("outputTokens") ?: 0,
        row.long("reasoningTokens") ?: 0,
        1,
    )
}

/** Durable events and row edits enter this fold once; no stream token walks historical records. */
class TranscriptTurnFold(private val cwd: String? = null) {
    var index = TranscriptTurnIndex.Empty
        private set

    private var ordinal = 0
    private var active: String? = null
    private var current: Int? = null
    private var turnSegments = mutableListOf<Int>()
    private val rowCards = mutableSetOf<Int>()
    private val stepStarts = mutableMapOf<Long, Long>()
    private val stepSpans = mutableMapOf<Long, Int>()
    private val firstAttempt = mutableMapOf<Long, Long>()
    private val retries = mutableMapOf<Long, Int>()
    private val tools = mutableMapOf<String, Int>()
    private val approvals = mutableMapOf<String, Pair<Int, String?>>()
    private val artifacts = TranscriptArtifactFold()
    private var boundary = 0L

    private fun trace() = active?.let(index.traces::get)

    private fun update(value: TranscriptTurnTrace) {
        index =
            TranscriptTurnIndex(
                index.root,
                index.traces.putting(value.id, value),
                index.ordinals,
                index.openOrdinal,
                index.rawCount,
                index.artifacts,
                index.artifactPositions,
                active,
                index.rawRows,
            )
    }

    private fun block(number: Int, value: TranscriptSegment) {
        index =
            TranscriptTurnIndex(
                replaceTranscriptNode(index.root, number, value),
                index.traces,
                index.ordinals,
                index.openOrdinal,
                index.rawCount,
                index.artifacts,
                index.artifactPositions,
                active,
                index.rawRows,
            )
    }

    fun row(position: Int, item: ChatItem) {
        val existing = index.rawRows[item.key]?.ordinal
        if (existing != null) {
            val value = transcriptSegment(index.root, existing) ?: return
            val card = hasCard(item)
            if (card != rowCards.contains(position)) {
                if (card) rowCards.add(position) else rowCards.remove(position)
                block(
                    existing,
                    value.copy(
                        cards =
                            if (card) (value.cards + position).sorted().toPersistentList()
                            else value.cards.filter { it != position }.toPersistentList(),
                    ),
                )
            }
            return
        }
        val standalone = item.role == ChatRole.User || item.command != null || active == null
        if (standalone) current = null
        var number = current
        if (number == null || standalone) {
            number = ordinal++
            val key = if (standalone) "row:${item.key}" else "tp${trace()?.startSeq}:${item.key}"
            val value = TranscriptSegment(key, if (standalone) null else active, persistentListOf())
            index =
                TranscriptTurnIndex(
                    appendTranscriptNode(
                        index.root,
                        TranscriptNode(number, value, transcriptPriority(number)),
                    ),
                    index.traces,
                    index.ordinals.putting(key, number),
                    index.openOrdinal ?: if (!standalone) number else null,
                    index.rawCount,
                    index.artifacts,
                    index.artifactPositions,
                    active,
                    index.rawRows,
                )
            if (!standalone) {
                current = number
                turnSegments.add(number)
            }
        }
        val value = transcriptSegment(index.root, number)!!
        val card = hasCard(item)
        if (card) rowCards.add(position)
        val next =
            value.copy(
                rows = value.rows.adding(position),
                finalAssistant =
                    if (item.role == ChatRole.Assistant && item.text.isNotBlank()) position
                    else value.finalAssistant,
                cards = if (card) value.cards.adding(position) else value.cards,
                tools = value.tools + if (item.tool != null) 1 else 0,
                messages =
                    value.messages +
                        if (item.role == ChatRole.Assistant && item.text.isNotBlank()) 1 else 0,
                thoughts = value.thoughts + if (item.reasoning.isNotBlank()) 1 else 0,
            )
        block(number, next)
        index =
            TranscriptTurnIndex(
                index.root,
                index.traces,
                index.ordinals,
                index.openOrdinal,
                maxOf(index.rawCount, position + 1),
                index.artifacts,
                index.artifactPositions,
                active,
                index.rawRows.putting(item.key, TranscriptRowLocation(position, number)),
            )
        if (standalone) current = null
    }

    private fun hasCard(item: ChatItem) =
        item.noticeKind != null ||
            item.tool?.let { tool ->
                val meta = tool.meta?.toJsonElement() as? JsonObject
                tool.state == ToolState.Succeeded &&
                    (meta?.containsKey("dsh-compose/view") == true ||
                        tool.name == "render_ui" && meta != null) ||
                    meta?.containsKey("dsh-compose/media") == true
            } == true

    fun apply(record: SessionRecord) {
        val data = record.data
        val step = data.long("step") ?: 0
        if (record.type == "turn/start") {
            if (active != null) finish(record.seq, record.time, "interrupted")
            val id = data.long("turn")?.toString() ?: return
            active = id
            artifacts.start(id)
            current = null
            turnSegments = mutableListOf()
            boundary = record.time
            stepStarts.clear()
            stepSpans.clear()
            firstAttempt.clear()
            tools.clear()
            approvals.clear()
            retries.clear()
            update(TranscriptTurnTrace(id, record.seq, record.time))
            index =
                TranscriptTurnIndex(
                    index.root,
                    index.traces,
                    index.ordinals,
                    ordinal,
                    index.rawCount,
                    index.artifacts,
                    index.artifactPositions,
                    active,
                    index.rawRows,
                )
            return
        }
        val trace = trace() ?: return
        artifacts.apply(record)
        when (record.type) {
            "step/start" -> {
                stepStarts[step] = record.time
                stepSpans[step] = trace.spans.size
                update(
                    trace.copy(
                        spans = trace.spans.adding(TranscriptSpan("model", -1, step, record.time)),
                    ),
                )
            }
            "assistant/attempt" ->
                streamBounds(data["stream"]).first?.let { time ->
                    firstAttempt[step] = firstAttempt[step]?.let { minOf(it, time) } ?: time
                }
            "llm/retry" -> retries[step] = (retries[step] ?: 0) + 1
            "assistant/message" -> {
                val message = data["message"] as? JsonObject
                val source = message?.get("source") as? JsonObject
                val bounds = streamBounds(data["stream"])
                val usage = decodeTranscriptTokenUsage(data["usage"])
                val span =
                    TranscriptSpan(
                        "model",
                        record.seq,
                        step,
                        stepStarts.remove(step) ?: boundary,
                        record.time,
                        provider = source?.string("provider"),
                        model = source?.string("model"),
                        firstToken =
                            listOfNotNull(bounds.first, firstAttempt.remove(step)).minOrNull(),
                        lastToken = bounds.second,
                        retries = retries.remove(step) ?: 0,
                        usage = usage,
                    )
                val at = stepSpans.remove(step)
                update(
                    trace.copy(
                        spans =
                            if (at == null) trace.spans.adding(span)
                            else trace.spans.replacingAt(at, span),
                        usage = trace.usage + usage,
                        steps = trace.steps + 1,
                    ),
                )
                boundary = record.time
            }
            "tool/call" -> {
                val id = data.string("callId") ?: return
                tools[id] = trace.spans.size
                val name = data.string("name").orEmpty()
                update(
                    trace.copy(
                        spans =
                            trace.spans.adding(
                                TranscriptSpan(
                                    if (name == "ask_user_question") "question" else "tool",
                                    record.seq,
                                    step,
                                    record.time,
                                    label = name,
                                    callId = id,
                                    arguments = data.string("arguments"),
                                ),
                            ),
                    ),
                )
            }
            "approval/asked" -> {
                val id = data.string("id") ?: return
                approvals[id] = trace.spans.size to data.string("callId")
                update(
                    trace.copy(
                        spans =
                            trace.spans.adding(
                                TranscriptSpan(
                                    "approval",
                                    record.seq,
                                    step,
                                    record.time,
                                    label = data.string("toolName"),
                                    callId = data.string("callId"),
                                ),
                            ),
                    ),
                )
            }
            "approval/decided" -> {
                val entry = approvals.remove(data.string("id")) ?: return
                val outcome = data.string("outcome")
                var spans =
                    trace.spans.replacingAt(
                        entry.first,
                        trace.spans[entry.first].copy(end = record.time, outcome = outcome),
                    )
                tools[entry.second]?.let { at ->
                    spans =
                        spans.replacingAt(
                            at,
                            spans[at].copy(start = record.time, outcome = outcome),
                        )
                }
                update(trace.copy(spans = spans))
            }
            "tool/result" -> {
                val message = data["message"] as? JsonObject
                val id = message?.string("toolCallId") ?: return
                val at = tools.remove(id) ?: return
                update(
                    trace.copy(
                        spans =
                            trace.spans.replacingAt(
                                at,
                                trace.spans[at].copy(
                                    end = record.time,
                                    failed = message.boolean("isError") == true,
                                ),
                            ),
                    ),
                )
                boundary = record.time
            }
            "step/end",
            "user/message" -> boundary = maxOf(boundary, record.time)
            "turn/end" ->
                finish(record.seq, record.time, (data["reason"] as? JsonObject)?.string("kind"))
        }
    }

    private fun finish(seq: Long, time: Long, reason: String?) {
        val trace = trace() ?: return
        val spans =
            trace.spans
                .map { if (it.end == null) it.copy(end = time, cutShort = true) else it }
                .toPersistentList()
        val (files, changes) = artifacts.finish(cwd)
        val finished =
            trace.copy(
                endSeq = seq,
                end = time,
                reason = reason,
                spans = spans,
                artifacts = files,
                changes = changes,
            )
        update(finished)
        if (turnSegments.isEmpty()) {
            val number = ordinal++
            val key = "footnote:${trace.startSeq}"
            val value = TranscriptSegment(key, trace.id, persistentListOf(), last = true)
            index =
                TranscriptTurnIndex(
                    appendTranscriptNode(
                        index.root,
                        TranscriptNode(number, value, transcriptPriority(number)),
                    ),
                    index.traces,
                    index.ordinals.putting(key, number),
                    index.openOrdinal,
                    index.rawCount,
                    index.artifacts,
                    index.artifactPositions,
                    active,
                    index.rawRows,
                )
        }
        turnSegments.forEachIndexed { i, number ->
            val value = transcriptSegment(index.root, number)!!
            // Every ended turn folds its process, however it ended; notices stay out as cards.
            val foldable = value.tools > 0 || value.thoughts > 0 || value.messages > 1
            block(
                number,
                value.copy(
                    folded = foldable,
                    foldEligible = foldable,
                    last = i == turnSegments.lastIndex,
                ),
            )
        }
        turnSegments.lastOrNull()?.let { last ->
            index =
                TranscriptTurnIndex(
                    index.root,
                    index.traces,
                    index.ordinals.putting("footnote:${trace.startSeq}", last),
                    index.openOrdinal,
                    index.rawCount,
                    index.artifacts,
                    index.artifactPositions,
                    active,
                    index.rawRows,
                )
        }
        var allFiles = index.artifacts
        var positions = index.artifactPositions
        files.forEach { file ->
            val at = positions[file.path]
            if (at == null) {
                positions = positions.putting(file.path, allFiles.size)
                allFiles = allFiles.adding(file)
            } else allFiles = allFiles.replacingAt(at, file)
        }
        index =
            TranscriptTurnIndex(
                index.root,
                index.traces,
                index.ordinals,
                index.openOrdinal,
                index.rawCount,
                allFiles,
                positions,
                active,
                index.rawRows,
            )
        active = null
        current = null
        index =
            TranscriptTurnIndex(
                index.root,
                index.traces,
                index.ordinals,
                null,
                index.rawCount,
                index.artifacts,
                index.artifactPositions,
                active,
                index.rawRows,
            )
    }

    private fun streamBounds(raw: JsonElement?): Pair<Long?, Long?> {
        var first: Long? = null
        var last: Long? = null
        fun see(time: Long) {
            first = first?.let { minOf(it, time) } ?: time
            last = last?.let { maxOf(it, time) } ?: time
        }
        (raw as? JsonArray).orEmpty().forEach { entry ->
            val value = entry as? JsonObject ?: return@forEach
            when (value.string("type")) {
                "chunk" ->
                    if (
                        (value["chunk"] as? JsonObject)?.string("type") in
                            setOf("text-delta", "reasoning-delta", "tool-call-delta")
                    )
                        value.long("time")?.let(::see)
                "text-chunks",
                "reasoning-chunks",
                "tool-call-chunks" -> {
                    var time = value.long("time0") ?: return@forEach
                    see(time)
                    (value["dt"] as? JsonArray).orEmpty().forEach {
                        time += (it as? JsonPrimitive)?.longOrNull ?: 0
                    }
                    see(time)
                }
            }
        }
        return first to last
    }
}

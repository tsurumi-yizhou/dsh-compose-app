package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.chat.CommandPresentation
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.chat.ToolPresentation
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.goals.GoalProjectionFold
import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.messages.InboxItem
import dev.fughetta.dshcompose.domain.messages.TranscriptNoticeData
import dev.fughetta.dshcompose.domain.messages.TranscriptRetryPlan
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.TodoList
import dev.fughetta.dshcompose.domain.transcript.*
import dev.fughetta.dshcompose.protocol.*
import java.util.TreeMap
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.*

/** Folds durable records once; token deltas update only the current drafts. */
class ChatTranscript {
    private val log = TreeMap<Long, SessionRecord>()
    private var fold = DurableFold()
    private var workspaceCwd: String? = null
    private val commandAcks = linkedMapOf<String, CommandResult>()
    private val localCommands = linkedMapOf<String, ChatItem>()
    private val goalProjection = GoalProjectionFold()
    val goal: GoalProjection?
        get() = goalProjection.current

    private val inboxProjection = InboxProjection()
    val inbox: PersistentList<InboxItem>
        get() = inboxProjection.current

    private val todoProjection = TodoProjection()
    val todos: TodoList?
        get() = todoProjection.current

    private val drafts = linkedMapOf<String, Draft>()
    var cursor: Long = -1
        private set

    var hasMoreHistory = false
        private set

    var title: String? = null
        private set

    var running = false
        private set

    var model: ModelSelection? = null
        private set

    /** The session's agent preset (`standard`, `ptc`, `minimal`, `cordis`, …); null until known. */
    var agentPreset: String? = null
        private set

    private var projectionModel: ModelSelection? = null
    private var hasSnapshot = false
    private var projectionTitle: String? = null
    private var mediaRevision = 0L

    val oldestSeq: Long?
        get() = log.firstKeyOrNull()

    fun recordsForShare(): PersistentList<SessionRecord> = log.values.toPersistentList()

    fun retryForNotice(key: String): TranscriptRetryPlan? {
        if (running || drafts.isNotEmpty() || localCommands.isNotEmpty()) return null
        val position = fold.position(key) ?: return null
        val notice = fold.items[position]
        if (notice.noticeKind !in setOf("error", "interrupted", "max-tokens")) return null
        val code = notice.notice?.code.orEmpty()
        if (
            notice.noticeKind == "error" &&
                (code in
                    setOf(
                        "MISSING_CREDENTIAL",
                        "AUTH",
                        "INVALID_CREDENTIAL",
                        "QUOTA",
                        "CLIENT_UPGRADE_REQUIRED",
                        "MODEL_UNSUPPORTED",
                        "UNSUPPORTED_REASONING_EFFORT",
                    ) || code.startsWith("ACCOUNT_"))
        )
            return null
        for (index in position + 1 until fold.items.size) if (
            fold.items[index].noticeKind != "retry"
        )
            return null
        val trace = fold.turnIndex.trace(notice.turnId) ?: return null
        if (trace.endSeq != notice.seq) return null
        var worked = false
        for (index in position - 1 downTo 0) {
            val item = fold.items[index]
            val seq = item.seq
            if (seq != null && seq < trace.startSeq) break
            if (item.role == ChatRole.Notice && item.noticeKind != "retry") break
            if (
                item.role == ChatRole.Tool ||
                    item.role == ChatRole.Assistant && item.text.isNotBlank()
            )
                worked = true
            if (item.role == ChatRole.User)
                return TranscriptRetryPlan(
                    if (
                        notice.noticeKind == "error" &&
                            !worked &&
                            item.attachments.isEmpty() &&
                            item.text.isNotBlank()
                    )
                        item.text
                    else null,
                )
        }
        return null
    }

    fun branchPointAfter(replySeq: Long): Long? =
        forkPointAfterReply(log.tailMap(replySeq, true).values, replySeq)

    fun branchPointBefore(promptSeq: Long): Long? =
        forkPointBeforePrompt(log.headMap(promptSeq, false).descendingMap().values, promptSeq)

    val items: PersistentList<ChatItem>
        get() = presentation.items

    val presentation: ChatPresentation
        get() {
            val durable = fold.items
            if (drafts.isEmpty())
                return ChatPresentation(
                    durable.addingAll(localCommands.values),
                    fold.order,
                    mediaRevision,
                    fold.generatedMedia,
                    fold.generatedMediaPaths,
                    fold.turnIndex,
                )
            var result = durable
            var order = fold.order
            for (draft in drafts.values) {
                val rows =
                    draft.items(!fold.containsKey(draft.rowKey)).filter {
                        it.role != ChatRole.Tool || !fold.containsKey(it.key)
                    }
                // Durable rows are ordered by their creation sequence, including later tool
                // updates.
                var low = 0
                var high = result.size
                while (low < high) {
                    val middle = (low + high).ushr(1)
                    if ((result[middle].seq ?: Long.MAX_VALUE) <= draft.startedAfterSeq)
                        low = middle + 1
                    else high = middle
                }
                result = result.addingAllAt(low, rows)
                for (row in rows) order = order.adding(row)
            }
            return ChatPresentation(
                result.addingAll(localCommands.values),
                order,
                mediaRevision,
                fold.generatedMedia,
                fold.generatedMediaPaths,
                fold.turnIndex,
            )
        }

    fun apply(update: SessionUpdate) {
        when (update) {
            is SessionUpdate.Reset -> drafts.clear()
            is SessionUpdate.Snapshot -> {
                if (!hasSnapshot || update.historyReplaced) log.clear()
                hasSnapshot = true
                workspaceCwd = update.header.string("cwd")
                if (log.isEmpty()) hasMoreHistory = update.hasMore
                update.records.forEach { log[it.seq] = it }
                cursor = update.cursor
                projectionTitle = (update.projections["values"] as? JsonObject)?.string("title")
                goalProjection.seed(update.projections)
                todoProjection.seed(update.projections)
                inboxProjection.seed(update.projections)
                val values = update.projections["values"] as? JsonObject
                val selection = values?.get("modelSelection") as? JsonObject
                projectionModel =
                    decodeModelSelection(selection?.get("next"))
                        ?: decodeModelSelection(selection?.get("lastUsed"))
                values?.string("agentPreset")?.let { agentPreset = it }
                drafts.clear()
                rebuild()
                model =
                    update.records
                        .lastOrNull { it.type == "model/selection" }
                        ?.let { decodeModelSelection(it.data) } ?: projectionModel ?: model
                val active = update.assistantStream?.get("activeAttempt") as? JsonObject
                if (active != null) {
                    val draft =
                        Draft(
                            active.requiredString("attemptId"),
                            active.long("turn") ?: 0,
                            active.long("step") ?: 0,
                            active.long("startedAfterSeq") ?: -1,
                        )
                    for (chunk in expandChunks(active["stream"])) draft.apply(chunk)
                    draft.nextIndex = active.long("nextIndex") ?: 0
                    drafts[draft.id] = draft
                    running = true
                }
            }
            is SessionUpdate.Record -> {
                val event = update.event
                val append = log.isEmpty() || event.seq > log.lastKey()
                log[event.seq] = event
                cursor = maxOf(cursor, event.seq)
                if (append) {
                    val mediaBefore = fold.generatedMedia
                    fold.apply(event)
                    todoProjection.apply(event)
                    goalProjection.apply(event)
                    inboxProjection.apply(event)
                    if (mediaBefore !== fold.generatedMedia) mediaRevision++
                } else rebuild()
                val commandId = event.data.string("commandId")
                if (commandId != null) {
                    if (event.type == "command/run")
                        commandAcks[commandId]?.let { result ->
                            if (fold.confirmCommand(result)) localCommands.remove(commandId)
                        }
                    if (event.type == "command/done" && fold.commandDone(commandId)) {
                        commandAcks.remove(commandId)
                        localCommands.remove(commandId)
                    }
                }
                when (event.type) {
                    "turn/start" -> running = true
                    "turn/end" -> running = false
                    "session/title" -> title = event.data.string("title")
                    "model/selection" -> model = decodeModelSelection(event.data)
                    "assistant/message",
                    "assistant/attempt" ->
                        drafts.entries.removeAll { (_, draft) ->
                            draft.turn == event.data.long("turn") &&
                                draft.step == event.data.long("step") &&
                                draft.startedAfterSeq < event.seq
                        }
                }
            }
            is SessionUpdate.Assistant -> {
                val frame = update.frame
                val id = frame.requiredString("attemptId")
                when (frame.string("type")) {
                    "start" -> {
                        drafts[id] =
                            Draft(
                                id,
                                frame.long("turn") ?: 0,
                                frame.long("step") ?: 0,
                                frame.long("startedAfterSeq") ?: cursor,
                            )
                        running = true
                    }
                    "chunk" ->
                        drafts[id]?.let { draft ->
                            val index = frame.long("index") ?: 0
                            if (index >= draft.nextIndex) {
                                draft.apply(frame["chunk"]?.objectValue() ?: return)
                                draft.nextIndex = index + 1
                            }
                        }
                    "end" -> {
                        val outcome = frame["outcome"] as? JsonObject
                        val committedSeq = outcome?.long("seq")
                        if (
                            outcome?.string("kind") != "committed" ||
                                committedSeq != null && log.containsKey(committedSeq)
                        ) {
                            drafts.remove(id)
                        } else drafts[id]?.streaming = false
                    }
                }
            }
            is SessionUpdate.Other -> Unit
        }
    }

    fun prependHistory(records: List<SessionRecord>, hasMore: Boolean) {
        val currentRunning = running
        val currentModel = model
        records.forEach { log[it.seq] = it }
        hasMoreHistory = hasMore
        rebuild()
        running = currentRunning
        if (currentModel != null) model = currentModel
    }

    fun confirmCommand(result: CommandResult, line: String) {
        val id = result.commandId ?: "local-${java.util.UUID.randomUUID()}"
        if (result.commandId != null && fold.commandDone(id)) return
        if (result.commandId != null) commandAcks[id] = result
        if (result.commandId != null && fold.confirmCommand(result)) localCommands.remove(id)
        else
            localCommands[id] =
                ChatItem(
                    "command:$id",
                    ChatRole.Notice,
                    text = result.text.orEmpty(),
                    command =
                        CommandPresentation(
                            id,
                            line,
                            true,
                            result.commandId != null && result.success,
                        ),
                )
    }

    fun confirmModel(selection: ModelSelection) {
        model = selection
    }

    fun confirmAgentPreset(preset: String) {
        agentPreset = preset
    }

    fun pendingApprovals(callIds: Set<String>) {
        fold.pendingApprovals(callIds)
    }

    private fun rebuild() {
        val previousFold = fold
        val previousPending = fold.pendingCalls
        fold = DurableFold(workspaceCwd).also { it.pendingApprovals(previousPending) }
        log.values.forEach(fold::apply)
        fold.shareMedia(previousFold)
        if (fold.generatedMedia !== previousFold.generatedMedia) mediaRevision++
        todoProjection.rebuild(log.values)
        inboxProjection.rebuild(log.values)
        goalProjection.rebuildRecords(log.values)
        for ((id, result) in commandAcks.toMap()) {
            if (fold.commandDone(id)) {
                commandAcks.remove(id)
                localCommands.remove(id)
            } else if (fold.confirmCommand(result)) localCommands.remove(id)
        }
        running =
            log.values.lastOrNull { it.type == "turn/start" || it.type == "turn/end" }?.type ==
                "turn/start"
        title =
            log.values.lastOrNull { it.type == "session/title" }?.data?.string("title")
                ?: projectionTitle
        model =
            log.values
                .lastOrNull { it.type == "model/selection" }
                ?.let { decodeModelSelection(it.data) } ?: projectionModel
    }
}

private class DurableFold(cwd: String? = null) {
    var generatedMedia: PersistentMap<String, MediaTranscriptItem> = persistentMapOf()
        private set

    var generatedMediaPaths: PersistentSet<String> = persistentSetOf()
        private set

    private var mediaPathCounts: PersistentMap<String, Int> = persistentMapOf()
    private val mediaCalls = mutableMapOf<String, MutableMap<String, MediaTranscriptItem>>()
    private val mediaCallKeys = mutableMapOf<String, String>()

    private fun mediaResult(callId: String, record: SessionRecord, meta: JsonElement?) {
        val media = generatedMediaFromToolMeta(meta)
        val previousKey = mediaCallKeys.remove(callId)
        val nextKey = media?.rid ?: callId
        if (previousKey != null) mediaCalls[previousKey]?.remove(callId)
        if (media != null) {
            mediaCallKeys[callId] = nextKey
            mediaCalls.getOrPut(nextKey) { mutableMapOf() }[callId] =
                MediaTranscriptItem(nextKey, callId, record.time, record.seq, media)
        }
        listOfNotNull(previousKey, if (media == null) null else nextKey).distinct().forEach { key ->
            val group = mediaCalls[key]
            val latest = group?.values?.maxByOrNull { it.seq }
            mediaEntry(
                key,
                latest?.let {
                    it.copy(
                        createdAt =
                            group.values.minOfOrNull { entry -> entry.createdAt } ?: it.createdAt,
                    )
                },
            )
            if (group.isNullOrEmpty()) mediaCalls.remove(key)
        }
    }

    private fun mediaEntry(key: String, entry: MediaTranscriptItem?) {
        val previous = generatedMedia[key]
        if (previous == entry) return
        previous?.media?.paths?.forEach { path ->
            val count = (mediaPathCounts[path] ?: 1) - 1
            if (count == 0) {
                mediaPathCounts = mediaPathCounts.removing(path)
                generatedMediaPaths = generatedMediaPaths.removing(path)
            } else mediaPathCounts = mediaPathCounts.putting(path, count)
        }
        generatedMedia =
            if (entry == null) generatedMedia.removing(key) else generatedMedia.putting(key, entry)
        entry?.media?.paths?.forEach { path ->
            mediaPathCounts = mediaPathCounts.putting(path, (mediaPathCounts[path] ?: 0) + 1)
            generatedMediaPaths = generatedMediaPaths.adding(path)
        }
    }

    fun shareMedia(previous: DurableFold) {
        val next = generatedMedia
        generatedMedia = previous.generatedMedia
        generatedMediaPaths = previous.generatedMediaPaths
        mediaPathCounts = previous.mediaPathCounts
        previous.generatedMedia.keys.filter { it !in next }.forEach { mediaEntry(it, null) }
        next.forEach { (key, value) -> mediaEntry(key, value) }
    }

    private val turnPresentation = TranscriptTurnFold(cwd)
    val turnIndex
        get() = turnPresentation.index

    var order: ChatMessageOrder = ChatMessageOrder.Empty
        private set

    var items: PersistentList<ChatItem> = persistentListOf()
        private set

    private val commands = mutableMapOf<String, Int>()

    fun commandDone(id: String) = commands[id]?.let { items[it].command?.done } == true

    fun confirmCommand(result: CommandResult): Boolean {
        val index = commands[result.commandId] ?: return false
        val row = items[index]
        if (row.command?.done != true)
            items =
                items.replacingAt(
                    index,
                    row.copy(
                        text = result.text.orEmpty(),
                        command = row.command?.copy(done = true, success = result.success),
                    ),
                )
        return true
    }

    private val tools = linkedMapOf<String, ToolBuilder>()
    private val unfinished = mutableSetOf<String>()
    private val approvalCalls = mutableMapOf<String, String>()
    private val keys = mutableSetOf<String>()

    fun position(key: String): Int? = turnPresentation.index.rawPosition(key)

    private var currentTurnId: String? = null
    var pendingCalls: Set<String> = emptySet()
        private set

    fun containsKey(key: String) = key in keys

    private fun append(item: ChatItem) {
        turnPresentation.row(items.size, item)
        items = items.adding(item)
        keys.add(item.key)
        order = order.adding(item)
    }

    private fun updateTool(tool: ToolBuilder) {
        val value = tool.item(tool.id in pendingCalls)
        turnPresentation.row(tool.index, value)
        items = items.replacingAt(tool.index, value)
    }

    fun pendingApprovals(callIds: Set<String>) {
        if (pendingCalls == callIds) return
        val changed = (pendingCalls - callIds) + (callIds - pendingCalls)
        pendingCalls = callIds
        changed.forEach { tools[it]?.let(::updateTool) }
    }

    private fun tool(id: String, name: String, args: String, seq: Long): ToolBuilder =
        tools.getOrPut(id) {
            ToolBuilder(id, name, args, seq, items.size, currentTurnId).also {
                append(it.item(id in pendingCalls))
                unfinished.add(id)
            }
        }

    fun apply(record: SessionRecord) {
        turnPresentation.apply(record)
        val data = record.data
        data.long("turn")?.let { currentTurnId = it.toString() }
        when (record.type) {
            "turn/start" -> currentTurnId = data.long("turn")?.toString()
            "command/run" -> {
                val id = data.requiredString("commandId")
                if (id !in commands) {
                    commands[id] = items.size
                    val line = "/${data.string("name").orEmpty()}${data.string("args").orEmpty()}"
                    append(
                        ChatItem(
                            "command:$id",
                            ChatRole.Notice,
                            seq = record.seq,
                            command = CommandPresentation(id, line, false, true),
                        ),
                    )
                }
            }
            "command/done" -> {
                val index = commands[data.string("commandId")] ?: return
                val row = items[index]
                items =
                    items.replacingAt(
                        index,
                        row.copy(
                            text = data.string("text").orEmpty(),
                            command =
                                row.command?.copy(
                                    done = true,
                                    success = data.string("kind") == "success",
                                ),
                        ),
                    )
            }
            "user/message" -> {
                val source = data["source"] as? JsonObject
                if (source?.string("kind") != "user") return
                val content = content(data)
                append(
                    ChatItem(
                        "u${record.seq}",
                        ChatRole.User,
                        content.text,
                        attachments = content.attachments,
                        seq = record.seq,
                        requestId = source.string("rpcId"),
                        turnId = currentTurnId,
                        messageId = data.string("id"),
                    ),
                )
            }
            "assistant/message" -> {
                val message = data["message"] as? JsonObject ?: return
                val content = content(message)
                val rowKey = "m${data.long("turn") ?: 0}.${data.long("step") ?: 0}"
                val interrupted = data.boolean("interrupted") == true
                if (
                    content.text.isNotBlank() ||
                        content.reasoning.isNotBlank() ||
                        content.attachments.isNotEmpty() ||
                        interrupted
                ) {
                    append(
                        ChatItem(
                            if (rowKey !in keys) rowKey else "a${record.seq}",
                            ChatRole.Assistant,
                            content.text,
                            content.reasoning,
                            content.attachments,
                            record.seq,
                            interrupted = interrupted,
                            turnId = data.long("turn")?.toString() ?: currentTurnId,
                            messageId = message.string("id"),
                        ),
                    )
                }
                content.calls.forEach { call ->
                    tool(
                        call.requiredString("id"),
                        call.string("name") ?: "",
                        call.string("arguments") ?: "",
                        record.seq,
                    )
                }
            }
            "tool/call" ->
                tool(
                    data.requiredString("callId"),
                    data.string("name") ?: "",
                    data.string("arguments") ?: "",
                    record.seq,
                )
            "tool/result" -> {
                val message = data["message"] as? JsonObject ?: return
                val builder = tool(message.requiredString("toolCallId"), "tool", "", record.seq)
                builder.result = content(message)
                builder.failed = message.boolean("isError") == true
                builder.failure = data["error"]?.toToolPayload()
                builder.meta = data["meta"]?.toToolPayload()
                mediaResult(builder.id, record, builder.meta?.toJsonElement())
                unfinished.remove(builder.id)
                updateTool(builder)
            }
            "approval/asked" ->
                data.string("callId")?.let { callId ->
                    approvalCalls[data.requiredString("id")] = callId
                    tools[callId]?.let {
                        it.approvalAsked = true
                        updateTool(it)
                    }
                }
            "approval/decided" ->
                approvalCalls[data.string("id")]?.let {
                    tools[it]?.let { tool ->
                        tool.outcome = data.string("outcome")
                        updateTool(tool)
                    }
                }
            "turn/end" -> {
                unfinished.forEach { id ->
                    tools.getValue(id).let {
                        it.ended = true
                        updateTool(it)
                    }
                }
                unfinished.clear()
                val reason = data["reason"] as? JsonObject
                val kind = reason?.string("kind")
                if (
                    kind in setOf("aborted", "error", "blocked", "max-tokens", "interrupted") &&
                        !(kind == "aborted" && items.lastOrNull()?.interrupted == true)
                ) {
                    val failure = reason?.get("error")?.toToolPayload()
                    append(
                        ChatItem(
                            "n${record.seq}",
                            ChatRole.Notice,
                            text = failure?.text("message").orEmpty(),
                            seq = record.seq,
                            noticeKind = kind,
                            turnId = currentTurnId,
                            notice = TranscriptNoticeData(code = failure?.text("code")),
                        ),
                    )
                }
            }
            "llm/retry" ->
                append(
                    ChatItem(
                        "n${record.seq}",
                        ChatRole.Notice,
                        text = (data["failure"] as? JsonObject)?.string("message").orEmpty(),
                        seq = record.seq,
                        noticeKind = "retry",
                        turnId = currentTurnId,
                        notice =
                            TranscriptNoticeData(
                                (data["failure"] as? JsonObject)?.string("code"),
                                data.long("retry"),
                                data.long("maxRetries"),
                                data.long("delayMs"),
                            ),
                    ),
                )
        }
    }
}

private class ToolBuilder(
    val id: String,
    val name: String,
    val args: String,
    val seq: Long,
    val index: Int,
    val turnId: String?,
) {
    var result: Content? = null
    var failed = false
    var ended = false
    var approvalAsked = false
    var outcome: String? = null
    var failure: ToolPayload? = null
    var meta: ToolPayload? = null

    fun item(pending: Boolean): ChatItem {
        val status =
            when {
                result != null && outcome != null && outcome != "allowed-once" -> ToolState.Denied
                result != null -> if (failed) ToolState.Failed else ToolState.Succeeded
                pending || approvalAsked && outcome == null && !ended -> ToolState.AwaitingApproval
                ended -> ToolState.Cancelled
                else -> ToolState.Running
            }
        return ChatItem(
            "tool:$id",
            ChatRole.Tool,
            result?.text.orEmpty(),
            attachments = result?.attachments.orEmpty(),
            seq = seq,
            tool = ToolPresentation(id, name, args, status, failure, meta),
            turnId = turnId,
        )
    }
}

internal data class Content(
    val text: String,
    val reasoning: String,
    val attachments: List<ChatAttachment>,
    val calls: List<JsonObject>,
)

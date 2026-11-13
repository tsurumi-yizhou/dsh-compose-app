package dev.fughetta.dshcompose.data.notify

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import dev.fughetta.dshcompose.domain.notify.*
import dev.fughetta.dshcompose.domain.notify.NotificationPrivacy
import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

class EngineNotificationsRepository
private constructor(
    private val sourceClient: ProtocolClient?,
    private val isActive: () -> Boolean,
    private val parent: CoroutineScope?,
    callbacks: NotificationSessionCallbacks?,
) : NotificationsRepository {
    constructor(
        client: ProtocolClient,
        isActive: () -> Boolean = { true },
    ) : this(client, isActive, null, null)

    constructor(
        scope: CoroutineScope,
        callbacks: NotificationSessionCallbacks,
    ) : this(null, { false }, scope, callbacks)

    private val client: ProtocolClient
        get() = checkNotNull(sourceClient)

    private val scope: CoroutineScope
        get() = checkNotNull(parent)

    private val selected = callbacks?.selected ?: { false }
    private val needed = callbacks?.needed ?: { emptySet<String>() }
    private val onEnd: (String, Long, ConnectionGeneration) -> Unit =
        callbacks?.onEnd ?: { _, _, _ -> }
    private val onRunning: (String) -> Unit = callbacks?.onRunning ?: {}
    private val onRemoved: (String) -> Unit = callbacks?.onRemoved ?: {}
    private val onChanged: () -> Unit = callbacks?.onChanged ?: {}
    private val onError: (String) -> Unit = callbacks?.onError ?: {}
    private var closed = false
    override var generation: ConnectionGeneration? = sourceClient?.let {
        requireNotNull(it.mux.state.value.generation) { "Notification connection is not ready" }
    }
        private set

    override val active: Boolean
        get() =
            !closed &&
                sourceClient != null &&
                isActive() &&
                client.mux.state.value.generation == generation

    override val pendingRequests: Map<String, ToolPayload.Obj>
        get() =
            if (active)
                client.mux.pendingRequests.value.mapValues {
                    it.value.toToolPayload() as ToolPayload.Obj
                }
            else emptyMap()

    override fun question(
        eventId: String,
    ): dev.fughetta.dshcompose.domain.messages.UserQuestionRequest? {
        if (!active) return null
        val event = client.mux.pendingRequests.value[eventId] ?: return null
        return dev.fughetta.dshcompose.data.messages.decodeQuestionRequest(event)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        if (!active) throw CancellationException("Notification connection retired")
        return block().also {
            if (!active) throw CancellationException("Notification connection retired")
        }
    }

    override suspend fun sessions(): List<SessionSummary> = guarded { client.listSessions() }

    override suspend fun cancel(sessionId: String): Unit = guarded { client.cancel(sessionId) }

    override suspend fun respondApproval(eventId: String, decision: String): Unit = guarded {
        client.mux.respondHostEvent(
            eventId,
            buildJsonObject {
                put("kind", "result")
                put("value", decision)
            },
            expectedGeneration = generation,
        )
    }

    override suspend fun answerQuestion(eventId: String, answers: List<QuestionAnswer>): Unit =
        guarded {
            client.answerQuestion(eventId, answers, checkNotNull(generation))
        }

    override suspend fun rejectQuestion(eventId: String): Unit = guarded {
        client.rejectQuestion(eventId, checkNotNull(generation))
    }

    private suspend fun tail(id: String, count: Int): Pair<JsonObject, List<JsonObject>>? =
        guarded {
            val projection = client.projections(id) ?: return@guarded null
            val seq = projection.number("asOfSeq") ?: return@guarded null
            if (seq < 0) return@guarded null
            val page =
                client.connection.call(
                    "session/page",
                    buildJsonObject {
                        putJsonObject("request") {
                            putJsonObject("address") {
                                put("kind", "session")
                                put("sessionId", id)
                            }
                            put("throughSeq", seq)
                            put("maxMessages", count)
                        }
                    },
                ) as? JsonObject ?: return@guarded null
            projection to (page["records"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }

    /** One bounded tail only after an idle edge, never a session watch or a history walk. */
    override suspend fun turn(sessionId: String): NotificationTurn? {
        val id = sessionId
        val (projection, records) = tail(id, 1) ?: return null
        var end: JsonObject? = null
        var reply: String? = null
        for (record in records) {
            val data = record["data"] as? JsonObject ?: continue
            when (record.text("type")) {
                "turn/start" -> {
                    end = null
                    reply = null
                }
                "turn/end" -> end = record
                "assistant/message" ->
                    ((data["message"] as? JsonObject)?.get("content") as? JsonArray)?.let { content
                        ->
                        val text = StringBuilder()
                        for (part in content) {
                            val block = part as? JsonObject ?: continue
                            if (block.text("type") == "text" && text.length < 16_384)
                                text.append(block.text("text").orEmpty().take(16_384 - text.length))
                        }
                        reply = text.toString()
                    }
            }
        }
        val record = end ?: return null
        val reason = (record["data"] as? JsonObject)?.get("reason") as? JsonObject ?: return null
        val failure = reason["error"] as? JsonObject ?: reason["failure"] as? JsonObject
        return NotificationTurn(
            record.number("seq") ?: return null,
            reason.text("kind") ?: return null,
            (projection["values"] as? JsonObject)?.text("title"),
            NotificationPrivacy.snippet(reply),
            failure?.text("code"),
        )
    }

    override suspend fun call(
        sessionId: String,
        callId: String,
        toolName: String,
    ): NotificationCall? {
        val id = sessionId
        val name = toolName
        if (!Regex("[A-Za-z0-9_.:-]{1,160}").matches(name)) return null
        val (_, records) = tail(id, 4) ?: return null
        fun exact(
            candidateId: String?,
            candidateName: String?,
            arguments: String?,
        ): NotificationCall? {
            if (
                candidateId != callId ||
                    candidateName != name ||
                    arguments == null ||
                    arguments.length > 3072
            )
                return null
            val args =
                runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
                    ?: return null
            val text = "$name\n$args"
            return NotificationPrivacy.exact(text)?.let { NotificationCall(name, it) }
        }
        for (record in records.asReversed()) {
            val data = record["data"] as? JsonObject ?: continue
            if (record.text("type") == "tool/call")
                exact(data.text("callId"), data.text("name"), data.text("arguments"))?.let {
                    return it
                }
            if (record.text("type") == "assistant/message") {
                val content =
                    (data["message"] as? JsonObject)?.get("content") as? JsonArray ?: continue
                for (part in content.mapNotNull { it as? JsonObject }) if (
                    part.text("type") == "tool-call"
                )
                    exact(part.text("id"), part.text("name"), part.text("arguments"))?.let {
                        return it
                    }
            }
        }
        return null
    }

    override var repository: NotificationsRepository? = null
        private set

    var revision = 0L
    var seeded = false
    var connectionCut = 0L
    var statusSinceConnect = false
    var bootstrap: Job? = null
    var metadataJob: Job? = null
    override val running = linkedMapOf<String, NotificationRun>()
    var parents = mutableMapOf<String, String>()
    override var subagents = mutableSetOf<String>()
    override val titles =
        object : LinkedHashMap<String, String>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
                size > 2048
        }
    override val changes = mutableMapOf<String, Long>()
    val reading = mutableSetOf<String>()
    override val seen =
        object : LinkedHashMap<String, Long>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) =
                size > 1024
        }

    override fun current(generation: ConnectionGeneration) =
        selected() &&
            this.generation == generation &&
            repository?.active == true &&
            repository?.generation == generation

    override fun connect(repository: NotificationsRepository?) {
        if (closed) return
        val generation = repository?.generation
        bootstrap?.cancel()
        metadataJob?.cancel()
        this.repository = repository
        this.generation = generation
        seeded = false
        connectionCut = revision
        statusSinceConnect = false
        if (repository == null || generation == null) return
        val owner = this
        val revision = owner.revision
        owner.bootstrap = scope.launch {
            // The parent can supply the metadata snapshot it already owns before this read begins.
            delay(150)
            if (owner.seeded || !current(generation)) return@launch
            try {
                val rows = withTimeout(5_000) { repository.sessions() }
                val prepared = withContext(Dispatchers.Default) { prepare(rows) }
                if (current(generation)) seed(owner, prepared, revision)
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
            } catch (_: Exception) {
                if (current(generation)) onError("notify/sessions")
            }
        }
    }

    private data class Prepared(
        val ids: Set<String>,
        val active: Set<String>,
        val parents: MutableMap<String, String>,
        val subagents: MutableSet<String>,
        val titles: List<Pair<String, String>>,
    )

    private fun prepare(rows: List<SessionSummary>): Prepared {
        val ids = HashSet<String>(rows.size)
        val active = mutableSetOf<String>()
        val parents = mutableMapOf<String, String>()
        val subagents = mutableSetOf<String>()
        rows.forEach { row ->
            ids.add(row.sessionId)
            if (row.running) active.add(row.sessionId)
            if (row.origin == "subagent") {
                subagents.add(row.sessionId)
                row.parentSessionId?.let { parents[row.sessionId] = it }
            }
        }
        val candidates =
            (rows.asSequence().filter { it.running } + rows.asSequence())
                .distinctBy { it.sessionId }
                .take(2048)
        val titles =
            candidates
                .mapNotNull { row ->
                    row.title?.let {
                        row.sessionId to NotificationPrivacy.snippet(it.take(256), 80).orEmpty()
                    }
                }
                .toList()
        return Prepared(ids, active, parents, subagents, titles)
    }

    override fun sessions(rows: List<SessionSummary>) {
        val owner = this
        val generation = owner.generation ?: return
        if (!current(generation)) return
        val seedAllowed = !owner.seeded && !owner.statusSinceConnect
        val cut = owner.connectionCut
        owner.metadataJob?.cancel()
        owner.metadataJob = scope.launch {
            val prepared = withContext(Dispatchers.Default) { prepare(rows) }
            if (!current(generation)) return@launch
            if (seedAllowed && !owner.seeded && !owner.statusSinceConnect)
                seed(owner, prepared, cut)
            else metadata(owner, prepared, cut)
        }
    }

    private fun metadata(host: EngineNotificationsRepository, prepared: Prepared, cut: Long) {
        // Preserve parent links learned from Host events while the snapshot was being reduced.
        host.changes
            .filterValues { it > cut }
            .keys
            .forEach { id ->
                val parent = host.parents[id]
                if (parent != null) prepared.parents[id] = parent else prepared.parents.remove(id)
                if (id in host.subagents) prepared.subagents.add(id)
                else prepared.subagents.remove(id)
            }
        host.subagents = prepared.subagents
        host.parents = prepared.parents
        prepared.titles.forEach { (id, title) -> host.titles[id] = title }
    }

    private fun seed(host: EngineNotificationsRepository, prepared: Prepared, cut: Long) {
        val generation = host.generation ?: return
        host.seeded = true
        metadata(host, prepared, cut)
        host.running.keys
            .filter { it !in prepared.active && (host.changes[it] ?: 0) <= cut }
            .forEach { id ->
                host.running.remove(id)
                val revision = ++host.revision
                host.changes[id] = revision
                if (id in prepared.ids) onEnd(id, revision, generation)
                else {
                    host.parents.remove(id)
                    host.subagents.remove(id)
                    host.titles.remove(id)
                    onRemoved(id)
                }
            }
        prepared.active.forEach { id ->
            if ((host.changes[id] ?: 0) <= cut)
                host.running.putIfAbsent(
                    id,
                    NotificationRun(System.currentTimeMillis(), ++host.revision),
                )
        }
        host.trimChanges()
        onChanged()
    }

    override fun event(value: dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj) {
        val event = value.toJsonElement() as JsonObject
        val generation = generation?.takeIf(::current) ?: return
        if (event.text("type") != "emit") return
        val args = event["args"] as? JsonArray ?: return
        when (event.text("event")) {
            "api-session/status" -> {
                val id = (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return
                val active = (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull ?: return
                this.statusSinceConnect = true
                val old = this.running[id]
                if (active) {
                    val revision = ++this.revision
                    this.changes[id] = revision
                    this.running[id] =
                        NotificationRun(old?.since ?: System.currentTimeMillis(), revision)
                    onRunning(id)
                } else if (old != null || id !in this.changes) {
                    this.running.remove(id)
                    val revision = ++this.revision
                    this.changes[id] = revision
                    onEnd(id, revision, generation)
                }
                trimChanges()
                onChanged()
            }
            "api-session/added" -> {
                val row = args.getOrNull(0) as? JsonObject ?: return
                val id = row.text("sessionId") ?: return
                this.changes[id] = ++this.revision
                if (row.text("origin") == "subagent") {
                    this.subagents.add(id)
                    row.text("parentSessionId")?.let { this.parents[id] = it }
                } else {
                    this.subagents.remove(id)
                    this.parents.remove(id)
                }
                val title =
                    ((row["projections"] as? JsonObject)?.get("values") as? JsonObject)?.text(
                        "title",
                    )
                title?.let { this.titles[id] = NotificationPrivacy.snippet(it, 80).orEmpty() }
            }
            "api-session/removed" ->
                (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.let { id ->
                    this.running.remove(id)
                    this.parents.remove(id)
                    this.subagents.remove(id)
                    this.titles.remove(id)
                    this.changes[id] = ++this.revision
                    onRemoved(id)
                    onChanged()
                }
        }
    }

    private fun trimChanges() {
        if (changes.size > 4096) changes.keys.retainAll(running.keys + needed() + reading)
    }

    override fun root(id: String): String {
        var result = id
        val seen = mutableSetOf<String>()
        repeat(8) {
            if (!seen.add(result)) return id
            result = parents[result] ?: return result
        }
        return result
    }

    override fun close() {
        closed = true
        bootstrap?.cancel()
        metadataJob?.cancel()
        repository = null
        generation = null
    }

    override suspend fun awaitMetadata() {
        if (!seeded) {
            metadataJob?.join()
            if (!seeded) bootstrap?.join()
        }
    }

    override fun reading(id: String, active: Boolean) {
        if (active) reading.add(id) else reading.remove(id)
    }

    override fun recordSeen(id: String, sequence: Long) {
        seen[id] = sequence
    }

    override fun rememberTitle(id: String, title: String) {
        titles[id] = title
    }
}

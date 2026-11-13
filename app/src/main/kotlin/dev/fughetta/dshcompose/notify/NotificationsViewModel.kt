package dev.fughetta.dshcompose.notify

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.R as UiR
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.notify.NotificationCall
import dev.fughetta.dshcompose.domain.notify.NotificationPrivacy
import dev.fughetta.dshcompose.domain.notify.NotificationSessionCallbacks
import dev.fughetta.dshcompose.domain.notify.NotificationsRepository
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.phone.*
import dev.fughetta.dshcompose.phoneui.PhoneTurnFinish
import dev.fughetta.dshcompose.ui.notify.*
import java.util.UUID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Host-wide events only; three bounded read workers share small completion/approval tails. */
class NotificationsViewModel(
    context: Context,
    parent: CoroutineScope? = null,
    private val notifications: Notifications,
    private val settings: NotificationSettingsViewModel,
    private val createSessions:
        (CoroutineScope, NotificationSessionCallbacks) -> NotificationsRepository,
    private val onOpen: (NotificationTarget) -> Unit,
    private val onError: (String) -> Unit = {},
    internal val skipSession: (String, String) -> Boolean = { _, _ -> false },
    private val awaitPhoneReturn:
        suspend (NotificationsRepository, ConnectionGeneration, String) -> PhoneTurnFinish? =
        { _, _, _ ->
            null
        },
    private val ensureBotOwnership: suspend (String, String) -> Boolean = { host, id ->
        skipSession(host, id)
    },
) : ViewModel() {
    private val parentScope = parent ?: viewModelScope

    override fun onCleared() {
        close()
    }

    internal val app = context.applicationContext
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    internal val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    internal val mutable = settings.mutable
    val state: StateFlow<NotificationSettingsState> = settings.state
    private val mutableBanner = MutableStateFlow<NotificationBanner?>(null)
    val banner: StateFlow<NotificationBanner?> = mutableBanner.asStateFlow()
    internal val hosts = mutableMapOf<String, Host>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val work = linkedMapOf<String, Work>()
    internal var closed = false
    private var foreground = false
    private var viewed: NotificationTarget? = null
    private var bannerId = 0L
    internal var liveJob: Job? = null
    internal var ending: LiveContent? = null
    private var endingTimer: Job? = null
    private var refreshJob: Job? = null
    private val phone = PhoneControl.get(app)
    private var phoneRequest: Pending? = null
    private val promptOwner = Any()

    internal inner class Host(val id: String, val local: Boolean) {
        val requests = linkedMapOf<String, Pending>()
        val sessions =
            createSessions(
                scope,
                NotificationSessionCallbacks(
                    selected = { !closed && hosts[id] === this },
                    needed = {
                        work.values
                            .filter { it.host === this }
                            .mapNotNullTo(mutableSetOf()) { it.session }
                    },
                    onEnd = { session, revision, generation ->
                        enqueue("$id:end:$session", Work(this, generation, session, revision))
                    },
                    onRunning = { session -> work.remove("$id:end:$session") },
                    onRemoved = { session ->
                        requests.values
                            .filter { it.root == session || it.agentId == session }
                            .map { it.eventId }
                            .forEach { withdraw(this, it) }
                        notifications.notifier.cancelGroup(group(id, session))
                        clearBanner(id, session)
                    },
                    onChanged = this@NotificationsViewModel::updateLive,
                    onError = ::report,
                ),
            )
    }

    internal class Pending(
        val host: Host,
        val generation: ConnectionGeneration,
        val eventId: String,
        val agentId: String,
        val event: ToolPayload.Obj,
        val approval: Boolean,
    ) {
        val nativePromptId = "engine:${UUID.randomUUID()}"
        var root = agentId
        var delay: Job? = null
        var call: NotificationCall? = null
        var alert: Alert? = null
        var answering = false
    }

    internal class Alert(
        val target: NotificationTarget,
        val key: String,
        val kind: String,
        val title: String,
        val text: String,
        val publicText: String,
        val exact: String? = null,
    ) {
        val attention
            get() = kind == "approval" || kind == "question"
    }

    private class Work(
        val host: Host,
        val generation: ConnectionGeneration,
        val session: String? = null,
        val revision: Long? = null,
        val pending: Pending? = null,
    )

    init {
        repeat(3) {
            scope.launch {
                for (signal in wake) {
                    while (work.isNotEmpty()) {
                        val key = work.keys.first()
                        val next = work.remove(key) ?: continue
                        if (!current(next.host, next.generation)) continue
                        next.session?.let { next.host.sessions.reading(it, true) }
                        try {
                            withTimeout(20_000) {
                                if (next.pending != null) readRequest(next.pending)
                                else readEnd(next)
                            }
                        } catch (error: CancellationException) {
                            if (error !is TimeoutCancellationException) throw error
                            else if (current(next.host, next.generation)) report("notify/read")
                        } catch (_: Exception) {
                            if (current(next.host, next.generation)) report("notify/read")
                        } finally {
                            next.session?.let { next.host.sessions.reading(it, false) }
                        }
                    }
                }
            }
        }
        refresh()
        updateLive()
    }

    fun connect(
        host: String,
        local: Boolean,
        repository: NotificationsRepository?,
    ) {
        if (closed || host.isBlank()) return
        val generation = repository?.generation
        val previous = hosts[host]
        if (
            previous != null &&
                previous.sessions.repository === repository &&
                previous.sessions.generation == generation &&
                previous.local == local
        )
            return
        val owner = previous?.takeIf { it.local == local } ?: Host(host, local)
        previous?.let(::retireRequests)
        if (previous !== owner) previous?.sessions?.close()
        hosts[host] = owner
        owner.sessions.connect(repository)
        work.entries.removeAll { it.value.host === owner }
        if (repository == null || generation == null) {
            if (!local) hosts.remove(host)
            updateLive()
            return
        }
        repository.pendingRequests.values.forEach { onHostEvent(host, it) }
    }

    fun sessions(host: String, rows: List<SessionSummary>) {
        hosts[host]?.sessions?.sessions(rows)
    }

    fun onHostEvent(host: String, event: ToolPayload.Obj) {
        val owner = hosts[host] ?: return
        val generation = owner.sessions.generation ?: return
        if (!current(owner, generation)) return
        if (event.text("type") == "cancel") {
            event.text("eventId")?.let { withdraw(owner, it) }
            return
        }
        if (
            event.text("type") == "waterfall" &&
                event.text("event") in setOf("approval/request", "user-questions/request")
        ) {
            val id = event.text("eventId") ?: return
            if (owner.requests.containsKey(id)) return
            val agent = event.text("agentId") ?: return
            val pending =
                Pending(
                    owner,
                    generation,
                    id,
                    agent,
                    event,
                    event.text("event") == "approval/request",
                )
            owner.requests[id] = pending
            pending.delay = scope.launch {
                delay(800)
                if (current(pending))
                    enqueue("${host}:request:$id", Work(owner, generation, pending = pending))
            }
            return
        }
        owner.sessions.event(event)
    }

    private fun enqueue(key: String, value: Work) {
        work[key] = value
        wake.trySend(Unit)
    }

    internal fun current(host: Host, generation: ConnectionGeneration): Boolean =
        host.sessions.current(generation)

    internal fun current(pending: Pending) =
        current(pending.host, pending.generation) &&
            pending.host.requests[pending.eventId] === pending &&
            pending.host.sessions.repository?.pendingRequests?.get(pending.eventId) == pending.event

    private suspend fun readEnd(work: Work) {
        val host = work.host
        val id = work.session ?: return
        host.sessions.awaitMetadata()
        fun valid() =
            current(host, work.generation) &&
                id !in host.sessions.running &&
                host.sessions.changes[id] == work.revision
        if (!valid() || id in host.sessions.subagents || skipSession(host.id, id)) return
        if (ensureBotOwnership(host.id, id) || !valid()) return
        val readback = host.sessions.repository ?: return
        val outcome = withContext(Dispatchers.Default) { readback.turn(id) } ?: return
        if (!valid() || (host.sessions.seen[id] ?: -1) >= outcome.seq) return
        host.sessions.recordSeen(id, outcome.seq)
        outcome.title?.let {
            host.sessions.rememberTitle(id, NotificationPrivacy.snippet(it, 80).orEmpty())
        }
        val kind =
            when (outcome.kind) {
                "completed",
                "max-tokens" -> "done"
                "aborted" -> "stopped"
                else -> "failed"
            }
        val text = notificationCompletionText(app, outcome)
        val alert =
            Alert(
                NotificationTarget(host.id, id),
                "turn",
                kind,
                title(host, id),
                text,
                publicText(kind),
            )
        val phoneResult =
            if (host.local)
                awaitPhoneReturn(host.sessions.repository ?: return, work.generation, id)
            else null
        if (!valid() || phoneResult != null && !phoneResult.current) return
        if (phoneResult?.notified != true) deliver(alert)
        if (!foreground && allows(kind)) {
            ending =
                liveContent(
                    alert,
                    if (kind == "done") LiveContent.Kind.DONE else LiveContent.Kind.FAILED,
                )
            endingTimer?.cancel()
            endingTimer = scope.launch {
                delay(15_000)
                ending = null
                updateLive()
            }
        }
        updateLive()
    }

    private suspend fun readRequest(pending: Pending) {
        if (!current(pending)) return
        val host = pending.host
        host.sessions.awaitMetadata()
        if (!current(pending)) return
        pending.root = host.sessions.root(pending.agentId)
        if (
            skipSession(host.id, pending.root) ||
                ensureBotOwnership(host.id, pending.root) ||
                !current(pending)
        )
            return
        val request = pending.event["request"] as? ToolPayload.Obj ?: return
        val kind = if (pending.approval) "approval" else "question"
        val readback = host.sessions.repository ?: return
        val callId = request["callId"]?.primitiveContent
        val toolName = request["toolName"]?.primitiveContent
        val call =
            if (pending.approval && callId != null && toolName != null)
                try {
                    withContext(Dispatchers.Default) {
                        readback.call(pending.agentId, callId, toolName)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            else null
        if (!current(pending)) return
        pending.call = call
        val line =
            if (pending.approval)
                call?.exactText?.let { NotificationPrivacy.snippet(it, 120) }
                    ?: AppStrings.get(
                        app,
                        UiR.string.notification_controls_approval_tool,
                        NotificationPrivacy.snippet(request.text("toolName"), 80).orEmpty(),
                    )
            else
                request
                    .arrayItems("questions")
                    ?.firstOrNull()
                    ?.let { (it as? ToolPayload.Obj)?.text("question") }
                    ?.let { NotificationPrivacy.snippet(it) }
                    ?: AppStrings.get(app, UiR.string.notification_controls_question)
        val alert =
            Alert(
                NotificationTarget(host.id, pending.root, pending.eventId),
                pending.eventId,
                kind,
                title(host, pending.root),
                line,
                publicText(kind),
                call?.exactText,
            )
        pending.alert = alert
        if (!offerPhone(pending)) deliver(alert)
        updateLive()
    }

    private fun offerPhone(pending: Pending): Boolean {
        if (
            foreground ||
                !pending.host.local ||
                !current(pending) ||
                !phone.isEnabled() ||
                phone.tasks.snapshot().stopped
        )
            return false
        val alert = pending.alert ?: return false
        if (phoneRequest != null) return phoneRequest === pending
        phoneRequest = pending
        val task = phone.tasks.snapshot()
        val request =
            EnginePhonePrompt(
                promptId(pending),
                task.id.takeIf { task.active },
                pending.root,
                pending.host.id,
                if (pending.call != null) PromptKind.APPROVAL else PromptKind.REVIEW,
                pending.call?.exactText ?: alert.text,
            )
        phone.prompts.present(
            request,
            promptOwner,
            sourceCurrent = { current(pending) && phoneRequest === pending },
        ) { result ->
            if (phoneRequest === pending) phoneRequest = null
            if (!current(pending)) return@present
            val choice = result["decision"] as? String
            val decision =
                when {
                    choice == "allowed-once" && result["presented"] != "none" -> "allowed-once"
                    choice in setOf("rejected", "timeout") -> "rejected"
                    else -> null
                }
            if (pending.call != null && decision != null) answer(pending, decision, null)
            else if (choice != "stopped" && !foreground) post(alert, operational = true)
        }
        return true
    }

    private fun promptId(pending: Pending) = pending.nativePromptId

    fun respondAction(value: Map<String, Any?>): Boolean {
        if (value["action"] == "stop") {
            val host = value["host"] as? String ?: return false
            val session = value["session"] as? String ?: return false
            return stop(host, session)
        }
        val host = value["host"] as? String ?: return false
        val eventId = value["eventId"] as? String ?: return false
        val groupId = value["group"] as? String ?: return false
        val key = value["key"] as? String ?: return false
        val decision = value["decision"] as? String ?: return false
        if (decision !in setOf("allowed-once", "rejected")) return false
        val pending = hosts[host]?.requests?.get(eventId)
        if (
            pending == null ||
                !current(pending) ||
                pending.call == null ||
                groupId != group(host, pending.root) ||
                key != pending.eventId
        ) {
            notifications.notifier.finishAction(groupId, key, "gone")
            return true
        }
        answer(pending, decision, groupId to key)
        return true
    }

    /**
     * 「停止」 on the live card: cancel that conversation on the Host the card names. False for a card
     * that is stale — another generation, or a conversation that stopped meanwhile — so the tap
     * can't cancel a turn started since.
     */
    private fun stop(host: String, session: String): Boolean {
        val owner = hosts[host] ?: return false
        val client = owner.sessions.repository ?: return false
        val generation = owner.sessions.generation ?: return false
        if (
            !current(owner, generation) ||
                owner.sessions.running.keys.none { owner.sessions.root(it) == session }
        )
            return false
        scope.launch {
            try {
                client.cancel(session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The card stays up until the Host says otherwise; the settings page shows this.
                report("notify/stop")
            }
        }
        return true
    }

    private fun answer(pending: Pending, decision: String, notification: Pair<String, String>?) {
        scope.launch {
            val result = answerNow(pending, decision, null)
            if (
                current(pending.host, pending.generation) &&
                    pending.host.requests[pending.eventId].let { it == null || it === pending }
            )
                notification?.let {
                    notifications.notifier.finishAction(
                        it.first,
                        it.second,
                        when (result) {
                            "handled",
                            "answered" -> "handled"
                            "stale" -> "gone"
                            else -> "failed"
                        },
                    )
                }
        }
    }

    private suspend fun answerNow(pending: Pending, decision: String?, choice: String?): String {
        if (!current(pending)) return "stale"
        if (pending.answering) return "failed"
        pending.answering = true
        try {
            val client = pending.host.sessions.repository ?: return "stale"
            if (!current(pending)) return "stale"
            if (pending.approval) {
                val value = decision ?: return "stale"
                client.respondApproval(pending.eventId, value)
            } else {
                val question =
                    client.question(pending.eventId)?.questions?.singleOrNull() ?: return "stale"
                client.answerQuestion(
                    pending.eventId,
                    listOf(QuestionAnswer(question.id, listOf(choice ?: return "stale"))),
                )
            }
            if (
                !current(pending.host, pending.generation) ||
                    pending.host.requests[pending.eventId].let { it != null && it !== pending }
            )
                return "stale"
            if (pending.host.requests[pending.eventId] === pending)
                withdraw(pending.host, pending.eventId)
            return "handled"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!current(pending.host, pending.generation)) return "stale"
            report("notify/answer")
            return "failed"
        } finally {
            pending.answering = false
            updateLive()
        }
    }

    private fun withdraw(host: Host, eventId: String) {
        val pending = host.requests.remove(eventId) ?: return
        pending.delay?.cancel()
        work.remove("${host.id}:request:$eventId")
        phone.prompts.cancel(promptId(pending))
        if (phoneRequest === pending) phoneRequest = null
        notifications.notifier.cancel(group(host.id, pending.root), eventId)
        val banner = mutableBanner.value
        if (banner?.target?.host == host.id && banner.target.eventId == eventId)
            mutableBanner.value = null
        updateLive()
    }

    private fun retireRequests(host: Host) {
        host.requests.keys.toList().forEach { withdraw(host, it) }
    }

    fun visibility(foreground: Boolean, viewedHost: String? = null, viewedSession: String? = null) {
        if (closed) return
        val was = this.foreground
        this.foreground = foreground
        viewed =
            if (foreground && viewedHost != null && viewedSession != null)
                NotificationTarget(viewedHost, viewedSession)
            else null
        viewed?.let { target ->
            notifications.notifier.cancelGroup(group(target.host, target.sessionId))
            clearBanner(target.host, target.sessionId)
        }
        if (was && !foreground) {
            mutableBanner.value?.let { banner ->
                mutableBanner.value = null
                val pending =
                    banner.target.eventId?.let { hosts[banner.target.host]?.requests?.get(it) }
                if (pending == null || !offerPhone(pending))
                    pending?.alert?.let(::deliver)
                        ?: post(
                            Alert(
                                banner.target,
                                "turn",
                                banner.kind,
                                banner.title,
                                banner.text,
                                publicText(banner.kind),
                            ),
                        )
            }
            hosts.values
                .filter { it.local }
                .flatMap { it.requests.values }
                .toList()
                .forEach { pending -> if (!offerPhone(pending)) pending.alert?.let(::deliver) }
        }
        if (foreground) {
            ending = null
            endingTimer?.cancel()
        }
        updateLive()
    }

    fun onTap(value: Map<String, Any?>) {
        val host = value["host"] as? String ?: return
        val id = value["sessionId"] as? String ?: return
        if (host.isBlank() || id.isBlank()) return
        notifications.notifier.cancelGroup(
            group(
                host,
                if ((value["kind"] as? String)?.startsWith("dsbot-") == true) "dsbot:$id" else id,
            ),
        )
        clearBanner(host, id)
        onOpen(NotificationTarget(host, id, value["eventId"] as? String, value["kind"] as? String))
    }

    fun openBanner() {
        mutableBanner.value?.let { value ->
            mutableBanner.value = null
            onOpen(value.target)
        }
    }

    fun dismissBanner() {
        mutableBanner.value = null
    }

    private fun clearBanner(host: String, id: String) {
        mutableBanner.value
            ?.takeIf { it.target.host == host && it.target.sessionId == id }
            ?.let { mutableBanner.value = null }
    }

    private fun deliver(alert: Alert) {
        if (!allows(alert.kind) || skipSession(alert.target.host, alert.target.sessionId)) return
        if (
            viewed?.let {
                it.host == alert.target.host && it.sessionId == alert.target.sessionId
            } == true
        )
            return
        updateLive()
        if (
            viewed?.let {
                it.host == alert.target.host && it.sessionId == alert.target.sessionId
            } == true
        )
            return
        if (foreground)
            mutableBanner.value =
                NotificationBanner(
                    ++bannerId,
                    alert.target,
                    alert.title,
                    alert.text,
                    alert.attention,
                    alert.kind,
                )
        else post(alert)
    }

    private fun post(alert: Alert, operational: Boolean = false) {
        if (!operational && !allows(alert.kind) || closed) return
        try {
            notifications.notifier.show(
                ChatNotifier.Post(
                    group(alert.target.host, alert.target.sessionId),
                    alert.key,
                    alert.attention,
                    alert.title,
                    alert.text,
                    alert.publicText,
                    alert.target.sessionId,
                    alert.target.host,
                    alert.kind,
                    alert.target.eventId,
                    canApprove = alert.exact != null,
                    fullText = alert.exact,
                ),
            )
        } catch (_: Exception) {
            report("notify/post")
        }
    }

    internal fun allows(kind: String): Boolean {
        val p = mutable.value
        return p.enabled &&
            when (kind) {
                "done" -> p.done
                "approval" -> p.approval
                "question" -> p.question
                else -> p.failed
            }
    }

    internal val words: NotificationWords by lazy { notificationWords(app) }

    private fun publicText(kind: String) = NotificationCopy.publicText(kind, words)

    internal fun title(host: Host, id: String) =
        NotificationCopy.title(host.sessions.titles[id], words)

    private fun group(host: String, id: String) = "$host|$id"

    internal fun liveContent(alert: Alert, kind: LiveContent.Kind) =
        NotificationCopy.liveContent(
            kind = kind,
            title = alert.title,
            text = alert.text,
            publicText = alert.publicText,
            sessionId = alert.target.sessionId,
            host = alert.target.host,
            eventId = alert.target.eventId,
            words = words,
        )

    fun settingsEffect(effect: NotificationSettingsEffect) {
        when (effect) {
            NotificationSettingsEffect.Refresh -> refresh()
            is NotificationSettingsEffect.OpenSystem ->
                if (!notifications.openSettings(effect.channel)) report("notify/settings")
            NotificationSettingsEffect.OpenLiveSystem ->
                if (!notifications.openLiveSettings()) report("notify/settings")
            NotificationSettingsEffect.OpenNowBarSystem ->
                if (!notifications.openNowBarSettings()) report("notify/settings")
            NotificationSettingsEffect.EnableNowBar ->
                scope.launch {
                    val enabled =
                        try {
                            withContext(Dispatchers.IO) { NowBar.enable(app) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            false
                        }
                    if (enabled) refresh() else report("notify/nowbar")
                }
            NotificationSettingsEffect.Disabled -> {
                mutableBanner.value = null
                notifications.notifier.cancelAll()
            }
            NotificationSettingsEffect.ChannelsChanged -> {
                ending = null
                updateLive()
            }
            is NotificationSettingsEffect.Error -> report(effect.code)
        }
    }

    fun action(action: NotificationSettingsAction) = settings.action(action)

    fun claimPermission(id: String) = settings.claimPermission(id)

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val (ordinary, live) =
                    withContext(Dispatchers.IO) {
                        notifications.notifier.status() to LiveActivity.status(app)
                    }
                mutable.update {
                    it.copy(
                        loaded = true,
                        systemEnabled = ordinary["enabled"] == true,
                        doneChannel = ordinary["done"] == true,
                        attentionChannel = ordinary["attention"] == true,
                        liveSupported = live["supported"] == true,
                        liveAllowed = live["allowed"] == true,
                        liveSettings = live["settings"] == true,
                        nowBar = NowBar.parse(live["nowBar"]),
                        nowBarWritable = live["nowBarWritable"] == true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                report("notify/status")
            }
        }
    }

    private fun report(code: String) {
        if (closed) return
        mutable.update { it.copy(error = notificationFailureText(app, code)) }
        onError(code)
    }

    fun close() {
        if (closed) return
        hosts.values.toList().forEach(::retireRequests)
        closed = true
        lifetime.cancel()
        work.clear()
        wake.close()
        hosts.values.forEach { it.sessions.close() }
        hosts.clear()
        phone.prompts.detachOwner(promptOwner)
        LiveActivity.update(app, mutable.value.enabled && mutable.value.live, null)
    }
}

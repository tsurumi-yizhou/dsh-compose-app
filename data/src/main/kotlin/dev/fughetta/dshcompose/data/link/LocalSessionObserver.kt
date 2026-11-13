package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.account.EngineAccount
import dev.fughetta.dshcompose.data.notify.EngineNotificationsRepository
import dev.fughetta.dshcompose.domain.account.AccountRepository
import dev.fughetta.dshcompose.domain.account.accountCallbackOrigin
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.hosts.LocalHostLease
import dev.fughetta.dshcompose.domain.hosts.LocalHostObservation
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Local task ownership follows the local Host even while the visible chat uses another Host. */
class LocalSessionObserver(
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
    private val started: (String) -> Unit,
    private val finished: suspend (String) -> Unit,
    private val failed: (String) -> Unit,
    private val hostEvent: (dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj) -> Unit = {},
    private val summaries: (List<SessionSummary>) -> Unit = {},
    private val appVersion: String,
    private val locale: () -> String,
) : AutoCloseable {
    class Lease
    internal constructor(
        internal val client: ProtocolClient,
        val generation: ConnectionGeneration,
        val account: AccountRepository?,
    ) {
        val notifications = EngineNotificationsRepository(client)
        val view = LocalHostLease(generation, account, notifications)
    }

    private val publishedLease = MutableStateFlow<LocalHostLease?>(null)
    val view = LocalHostObservation(publishedLease.asStateFlow())
    var lease: Lease? = null
        private set(value) {
            field = value
            publishedLease.value = value?.view
        }

    private var identity: Pair<String, String>? = null
    private var connection: ProtocolClient? = null
    private var job: Job? = null
    private val running = mutableSetOf<String>()
    private val reads = Mutex()
    private var readingChanges: MutableMap<String, Boolean>? = null

    fun connect(value: Pair<String, String>?) {
        if (value == identity) return
        identity = value
        job?.cancel()
        connection?.close()
        connection = null
        replaceLease(null)
        if (value == null) return
        job = scope.launch {
            val transport = HostConnection(value.first)
            val client = ProtocolClient(transport)
            connection = client
            try {
                transport.login(value.second)
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        client.mux.hostEvents.collect { event ->
                            val current = lease ?: return@collect
                            if (current.client !== client) return@collect
                            if (
                                (event["type"] as? JsonPrimitive)?.contentOrNull != "emit" ||
                                    (event["event"] as? JsonPrimitive)?.contentOrNull !=
                                        "api-session/status"
                            ) {
                                hostEvent(
                                    event.toToolPayload()
                                        as dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
                                )
                                return@collect
                            }
                            val args = event["args"] as? JsonArray ?: return@collect
                            val id =
                                (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                                    ?: return@collect
                            val active =
                                (args.getOrNull(1) as? JsonPrimitive)?.booleanOrNull
                                    ?: return@collect
                            readingChanges?.set(id, active)
                            val wasRunning = id in running
                            if (active) {
                                running.add(id)
                                if (!wasRunning) started(id)
                            } else {
                                running.remove(id)
                                if (wasRunning) finished(id)
                            }
                            hostEvent(
                                event.toToolPayload()
                                    as dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
                            )
                        }
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        client.mux.state.collect { state ->
                            val generation =
                                state.generation.takeIf { state.state == ConnectionState.Connected }
                            if (generation == null) {
                                if (lease?.client === client) replaceLease(null)
                            } else if (
                                lease?.client !== client || lease?.generation != generation
                            ) {
                                replaceLease(
                                    Lease(
                                        client,
                                        generation,
                                        accountCallbackOrigin(value.first)?.let {
                                            EngineAccount(client, it, appVersion, locale)
                                        },
                                    ),
                                )
                                val current = lease
                                scope.launch {
                                    try {
                                        runningSessions()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        if (lease === current)
                                            failed("phone/local-sessions-unavailable")
                                    }
                                }
                            }
                        }
                    }
                    client.mux.ready()
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (connection === client) failed("phone/local-events-unavailable")
            } finally {
                if (connection === client) {
                    connection = null
                    replaceLease(null)
                }
                client.close()
            }
        }
    }

    private fun replaceLease(value: Lease?) {
        if (lease === value) return
        lease = value
        running.clear()
        readingChanges = null
        changed()
    }

    suspend fun runningSessions(): Set<String> = reads.withLock {
        val current = lease ?: throw ConnectionClosedException()
        val changes = mutableMapOf<String, Boolean>()
        readingChanges = changes
        try {
            val rows = current.client.listSessions()
            if (lease !== current) throw ConnectionClosedException()
            summaries(rows)
            val result = rows.filter { it.running }.mapTo(mutableSetOf()) { it.sessionId }
            changes.forEach { (id, active) -> if (active) result.add(id) else result.remove(id) }
            running.clear()
            running.addAll(result)
            result.toSet()
        } finally {
            if (readingChanges === changes) readingChanges = null
        }
    }

    suspend fun cancelRunning() {
        val current = lease ?: throw ConnectionClosedException()
        val sessions = runningSessions()
        var failure: Exception? = null
        for (id in sessions) {
            if (lease !== current) throw ConnectionClosedException()
            try {
                current.client.cancel(id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        identity = null
        job?.cancel()
        connection?.close()
        connection = null
        replaceLease(null)
    }
}

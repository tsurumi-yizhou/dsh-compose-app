package dev.fughetta.dshcompose.data.hosts

import dev.fughetta.dshcompose.data.link.ConnectionSession
import dev.fughetta.dshcompose.data.link.HostConnections
import dev.fughetta.dshcompose.data.link.LinkFailure
import dev.fughetta.dshcompose.data.link.LocalSessionObserver
import dev.fughetta.dshcompose.data.link.SelectedConnection
import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.data.sessions.EngineSessionsRepository
import dev.fughetta.dshcompose.data.sessions.TranscriptClock
import dev.fughetta.dshcompose.data.sessions.cachedTranscriptRepository
import dev.fughetta.dshcompose.data.sync.SessionMirror
import dev.fughetta.dshcompose.domain.commands.CommandResult
import dev.fughetta.dshcompose.domain.hosts.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.HostConnection
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process owner of service connections, selections, and their offline mirrors. */
class EngineHostsRepository(
    private val connections: HostConnections,
    private val readServices: suspend () -> List<RemoteHost> = {
        error("Service storage is missing")
    },
    private val writeServices: suspend (List<RemoteHost>, String?, String?) -> Unit = { _, _, _ ->
        error("Service storage is missing")
    },
    private val mirror: SessionMirror? = null,
) : HostsRepository {
    override suspend fun loadServices() = readServices()

    override suspend fun saveServices(
        hosts: List<RemoteHost>,
        tokenHost: String?,
        token: String?,
    ) {
        if (tokenHost == null || token == null) {
            writeServices(hosts, null, null)
            return
        }
        val host = hosts.single { it.id == tokenHost }
        val connection = HostConnection(host.url)
        try {
            val credential = connection.login(token)
            try {
                writeServices(hosts, host.id, credential.value)
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    try {
                        connection.revokeDevice(credential.deviceId)
                    } catch (cleanup: Exception) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
            connections.invalidate(host.id)
        } finally {
            connection.close()
        }
    }

    override suspend fun removeService(id: String) {
        val client = connections.ensure(id)
        val connection = client.connection as HostConnection
        val device = connection.credential ?: throw LinkFailure("device/unauthorized")
        connection.revokeDevice(device.deviceId)
        forgetService(id)
    }

    override suspend fun forgetService(id: String) {
        writeServices(readServices().filterNot { it.id == id }, null, null)
        connections.invalidate(id)
        mirror?.clearHost(id)
    }

    override fun state(host: String) = connections.state(host)

    override fun invalidate(host: String) = connections.invalidate(host)

    private class Selection(
        val connection: SelectedConnection,
        val pending: EngineMessagesRepository,
        val catalog: EngineSessionsRepository,
        val clock: TranscriptClock,
    )

    private val selections = mutableMapOf<HostSelection, Selection>()
    private val observers = mutableMapOf<LocalHostObservation, LocalSessionObserver>()

    fun registerSelection(
        connection: SelectedConnection,
        pending: EngineMessagesRepository,
        catalog: EngineSessionsRepository,
        clock: TranscriptClock = TranscriptClock(),
    ): HostSelection =
        synchronized(gate) {
            check(!closed)
            connection.view.also { selections[it] = Selection(connection, pending, catalog, clock) }
        }

    fun registerObserver(observer: LocalSessionObserver): LocalHostObservation =
        synchronized(gate) {
            check(!closed)
            observer.view.also { observers[it] = observer }
        }

    private fun selection(handle: HostSelection): Selection =
        synchronized(gate) {
            selections[handle] ?: throw CancellationException("Host selection retired")
        }

    private fun owner(handle: ConnectedHost): Pair<Selection, ConnectionSession>? =
        synchronized(gate) {
            selections.values.firstNotNullOfOrNull { selection ->
                selection.connection.session?.takeIf { it.view === handle }?.let { selection to it }
            }
        }

    override suspend fun open(
        selection: HostSelection,
        host: String,
        isCurrent: () -> Boolean,
    ): ConnectedHost? = selection(selection).connection.open(connections, host, isCurrent)?.view

    override fun disconnectSelection(selection: HostSelection, loading: Boolean) {
        selection(selection).connection.disconnect(loading)
    }

    override fun selectSession(selection: HostSelection, id: String?) {
        selection(selection).connection.selectSession(id)
    }

    override fun nextTranscriptRevision(selection: HostSelection): Long =
        selection(selection).clock.nextRevision()

    override fun releaseSelection(selection: HostSelection) {
        val previous = synchronized(gate) { selections.remove(selection) }
        previous?.connection?.close()
        previous?.catalog?.close()
    }

    override fun isLinkedTo(owner: ConnectedHost, host: String): Boolean =
        owner(owner)?.second?.isLinkedTo(connections, host) == true

    override suspend fun prompt(owner: ConnectedHost, id: String, text: String, timeZone: String?) {
        val current = owner(owner)?.second ?: throw CancellationException("Host connection retired")
        current.prompt(id, text, timeZone)
    }

    override fun subagentRoute(owner: ConnectedHost, id: String): SubagentAddress? =
        owner(owner)?.second?.subagentRoute(id)

    override fun conversation(
        owner: ConnectedHost,
        id: String,
        initialCommand: Pair<CommandResult, String>?,
    ): ConversationRepository {
        val (selection, connection) =
            owner(owner) ?: throw CancellationException("Host connection retired")
        val sink = checkNotNull(mirror)
        return connection.conversation(
            id,
            selection.pending,
            selection.catalog,
            selection.clock,
            initialCommand,
            { host, session, records ->
                sink.syncTranscript(host, session, records)
                Unit
            },
        )
    }

    override suspend fun cachedConversation(
        selection: HostSelection,
        host: String,
        id: String,
        home: String,
        limit: Int,
        current: () -> Boolean,
    ): ConversationRepository? {
        val owner = selection(selection)
        val cache = checkNotNull(mirror)
        val records =
            try {
                cache.cachedRecords(host, id, limit)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        if (!current() || synchronized(gate) { selections[selection] !== owner }) return null
        return cachedTranscriptRepository(
            host,
            id,
            home,
            records,
            owner.connection.parent,
            owner.pending,
            owner.catalog,
            owner.clock,
            hasMore = records.size >= limit,
            older = { before, count -> cache.cachedRecordsBefore(host, id, before, count) },
        )
    }

    private fun observer(handle: LocalHostObservation): LocalSessionObserver =
        synchronized(gate) {
            observers[handle] ?: throw CancellationException("Local observation retired")
        }

    override fun observeLocal(observer: LocalHostObservation, identity: Pair<String, String>?) =
        observer(observer).connect(identity)

    override suspend fun runningLocalSessions(observer: LocalHostObservation): Set<String> =
        observer(observer).runningSessions()

    override suspend fun cancelLocalSessions(observer: LocalHostObservation) =
        observer(observer).cancelRunning()

    override fun releaseLocalObserver(observer: LocalHostObservation) {
        val previous = synchronized(gate) { observers.remove(observer) }
        previous?.close()
    }

    override suspend fun syncHost(host: String): HostSyncOutcome =
        checkNotNull(mirror).syncHost(host, connections)

    override suspend fun clearHost(host: String) = checkNotNull(mirror).clearHost(host)

    private val gate = Any()
    private var closed = false

    override fun close() =
        synchronized(gate) {
            if (closed) return
            closed = true
            val selected = selections.values.toList()
            selections.clear()
            val watched = observers.values.toList()
            observers.clear()
            selected.forEach {
                it.connection.close()
                it.catalog.close()
            }
            watched.forEach { it.close() }
        }
}

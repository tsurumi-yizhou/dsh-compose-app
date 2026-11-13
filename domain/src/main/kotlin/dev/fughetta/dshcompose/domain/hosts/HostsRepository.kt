package dev.fughetta.dshcompose.domain.hosts

import dev.fughetta.dshcompose.domain.commands.CommandResult
import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.coroutines.flow.StateFlow

/** Saved host configuration, credentials and process-owned connections. */
interface HostsRepository : AutoCloseable {
    suspend fun open(
        selection: HostSelection,
        host: String,
        isCurrent: () -> Boolean,
    ): ConnectedHost?

    fun disconnectSelection(selection: HostSelection, loading: Boolean = false)

    fun selectSession(selection: HostSelection, id: String?)

    fun nextTranscriptRevision(selection: HostSelection): Long

    fun releaseSelection(selection: HostSelection)

    fun isLinkedTo(owner: ConnectedHost, host: String): Boolean

    suspend fun prompt(owner: ConnectedHost, id: String, text: String, timeZone: String? = null)

    fun subagentRoute(owner: ConnectedHost, id: String): SubagentAddress?

    fun conversation(
        owner: ConnectedHost,
        id: String,
        initialCommand: Pair<CommandResult, String>?,
    ): ConversationRepository

    suspend fun cachedConversation(
        selection: HostSelection,
        host: String,
        id: String,
        home: String,
        limit: Int,
        current: () -> Boolean,
    ): ConversationRepository?

    fun observeLocal(observer: LocalHostObservation, identity: Pair<String, String>?)

    suspend fun runningLocalSessions(observer: LocalHostObservation): Set<String>

    suspend fun cancelLocalSessions(observer: LocalHostObservation)

    fun releaseLocalObserver(observer: LocalHostObservation)

    suspend fun syncHost(host: String): HostSyncOutcome

    suspend fun clearHost(host: String)

    suspend fun loadServices(): List<RemoteHost>

    suspend fun saveServices(
        hosts: List<RemoteHost>,
        tokenHost: String? = null,
        token: String? = null,
    )

    suspend fun removeService(id: String)

    /** Explicit local erasure; leaves the server-side device grant in place. */
    suspend fun forgetService(id: String)

    fun state(host: String): StateFlow<LinkState>

    fun invalidate(host: String)
}

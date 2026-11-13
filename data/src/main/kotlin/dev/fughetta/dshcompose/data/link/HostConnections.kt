package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.hosts.ConnectionStatus
import dev.fughetta.dshcompose.domain.hosts.LinkState
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import dev.fughetta.dshcompose.protocol.ProtocolClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single owner of host connections. Callers ask for a link and get the same one back while it
 * lives; an idle link is closed after [idleTimeoutMs] so background hosts stop costing battery.
 * Opening is serialized per host: a burst of `ensure` calls produces one attempt.
 */
class HostConnections(
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    /** How to observe a client's mux state; tests pass a fake so transitions stay drivable. */
    private val watch: (ProtocolClient) -> StateFlow<ConnectionStatus> = { it.mux.state },
) {
    private class Host(var endpoint: suspend () -> ProtocolClient) {
        val state = MutableStateFlow<LinkState>(LinkState.Offline)
        val mutex = Mutex()
        @Volatile var client: ProtocolClient? = null
        var revision = 0L
        var owned = true
        var attempt = 0L
        var idle: Job? = null
        var watcher: Job? = null
    }

    private val hosts = mutableMapOf<String, Host>()
    private val providers = mutableListOf<suspend (String) -> (suspend () -> ProtocolClient)?>()
    private val retained = mutableMapOf<String, Int>()

    fun provideRemoteHosts(
        hosts: suspend () -> List<RemoteHost>,
        credential: suspend (String) -> String?,
    ) {
        provide { hostId ->
            if (!hostId.startsWith("compose-")) return@provide null
            suspend {
                val host =
                    hosts().firstOrNull { it.id == hostId }
                        ?: throw LinkFailure("service/not-found")
                val secret = credential(hostId) ?: throw LinkFailure("device/pairing-required")
                val connection =
                    dev.fughetta.dshcompose.protocol.HostConnection(
                        host.url,
                        credential =
                            dev.fughetta.dshcompose.protocol.HostCredential(
                                secret,
                                io.ktor.http.Url(host.url).let { url ->
                                    val name =
                                        if (':' in url.host && !url.host.startsWith('['))
                                            "[${url.host}]"
                                        else url.host
                                    if (url.port == url.protocol.defaultPort) name
                                    else "$name:${url.port}"
                                },
                            ),
                    )
                val client = ProtocolClient(connection)
                try {
                    client.mux.ready()
                    client
                } catch (error: Exception) {
                    client.close()
                    throw error
                }
            }
        }
    }

    /** Registers a durable way to rebuild a host's endpoint; the first one that answers wins. */
    fun provide(provider: suspend (String) -> (suspend () -> ProtocolClient)?) {
        synchronized(providers) { providers += provider }
    }

    private fun hostOrNull(hostId: String): Host? = synchronized(hosts) { hosts[hostId] }

    private fun host(hostId: String): Host =
        hostOrNull(hostId) ?: throw IllegalArgumentException("unknown-host")

    fun register(hostId: String, endpoint: suspend () -> ProtocolClient) {
        synchronized(hosts) {
            check(hostId !in hosts) { "host already registered: $hostId" }
            hosts[hostId] = Host(endpoint)
        }
    }

    fun state(hostId: String): StateFlow<LinkState> =
        hostOrNull(hostId)?.state?.asStateFlow()
            ?: MutableStateFlow(LinkState.Offline).asStateFlow()

    /** The client currently bound to the host, or null when nothing is attached. */
    fun link(hostId: String): ProtocolClient? {
        val host = hostOrNull(hostId) ?: return null
        return synchronized(host) { host.client }
    }

    fun hasLink(hostId: String): Boolean {
        val host = hostOrNull(hostId) ?: return false
        return synchronized(host) { host.client != null }
    }

    /** Registers the host when needed and opens or reuses its link. */
    suspend fun ensure(hostId: String, endpoint: suspend () -> ProtocolClient): ProtocolClient {
        val host =
            synchronized(hosts) { hosts[hostId] ?: Host(endpoint).also { hosts[hostId] = it } }
        // A fresh opener (new token, new port) replaces the stale one while no link is open.
        synchronized(host) { if (host.client == null) host.endpoint = endpoint }
        return ensure(hostId)
    }

    /**
     * Adopts a client another component owns (the local runtime controller): link state is
     * mirrored, but this owner never closes it.
     */
    internal fun offer(hostId: String, client: ProtocolClient) {
        val host =
            synchronized(hosts) {
                hosts.getOrPut(hostId) {
                    Host { (resolve(hostId) ?: throw LinkFailure("link/no-endpoint"))() }
                }
            }
        synchronized(host) {
            host.revision++
            host.owned = false
            host.client = client
            host.state.value = LinkState.Online(client.mux.state.value.generation)
            host.watcher?.cancel()
            host.watcher = scope.launch { watch(client).collect { publish(host, client, it) } }
        }
    }

    /** Forgets an adopted link without closing it; its owner decides what happens next. */
    fun detach(hostId: String) {
        val host = hostOrNull(hostId) ?: return
        synchronized(host) {
            if (!host.owned) {
                host.revision++
                host.watcher?.cancel()
                host.watcher = null
                host.client = null
                host.state.value = LinkState.Offline
            }
        }
    }

    /** Drops the cached link (credentials changed, engine restarted); the next ensure reopens. */
    fun invalidate(hostId: String) {
        if (hostOrNull(hostId) == null) return
        close(hostId)
    }

    /** Reserves a shared link until this caller releases it, including while it is opening. */
    internal suspend fun acquire(hostId: String): ProtocolClient {
        synchronized(hosts) {
            retained[hostId] = (retained[hostId] ?: 0) + 1
            hosts[hostId]?.let { host ->
                host.idle?.cancel()
                host.idle = null
            }
        }
        return try {
            ensure(hostId)
        } catch (error: Exception) {
            release(hostId)
            throw error
        }
    }

    /** Returns the live link, opening it if needed; the link stays until released and idle. */
    suspend fun ensure(hostId: String): ProtocolClient {
        if (hostOrNull(hostId) == null) {
            val endpoint = resolve(hostId) ?: throw LinkFailure("link/no-endpoint")
            synchronized(hosts) { hosts[hostId] = hosts[hostId] ?: Host(endpoint) }
        }
        val host = host(hostId)
        host.idle?.cancel()
        host.idle = null
        host.client?.let {
            return it
        }
        return host.mutex.withLock {
            host.client?.let {
                return@withLock it
            }
            val revision =
                synchronized(host) {
                    host.state.value = LinkState.Connecting(++host.attempt)
                    host.revision
                }
            try {
                val client = host.endpoint()
                synchronized(host) {
                    // The provider may publish a replacement while this opener is returning its
                    // result.
                    host.client?.let { current ->
                        if (current !== client && host.owned) client.close()
                        return@withLock current
                    }
                    if (host.revision != revision) {
                        if (host.owned) client.close()
                        throw LinkFailure("link/replaced")
                    }
                    host.client = client
                    host.state.value = LinkState.Online(client.mux.state.value.generation)
                    host.watcher?.cancel()
                    host.watcher = scope.launch {
                        watch(client).collect { publish(host, client, it) }
                    }
                    client
                }
            } catch (error: Exception) {
                synchronized(host) {
                    if (host.revision == revision && host.client == null)
                        host.state.value =
                            LinkState.Failed((error as? LinkFailure)?.code ?: "link/open-failed")
                }
                throw error
            }
        }
    }

    private suspend fun resolve(hostId: String): (suspend () -> ProtocolClient)? {
        val snapshot = synchronized(providers) { providers.toList() }
        for (provider in snapshot) provider(hostId)?.let {
            return it
        }
        return null
    }

    /** Mirrors a client's mux state so [state] answers "is this host reachable right now". */
    private fun publish(host: Host, client: ProtocolClient, mux: ConnectionStatus) =
        synchronized(host) {
            if (host.client !== client) return
            when (mux.state) {
                ConnectionState.Connected -> host.state.value = LinkState.Online(mux.generation)
                ConnectionState.Connecting,
                ConnectionState.Reconnecting -> {
                    host.attempt++
                    host.state.value = LinkState.Connecting(host.attempt)
                }
                ConnectionState.Unauthorized ->
                    host.state.value = LinkState.Failed("link/unauthorized")
                ConnectionState.Closed -> host.state.value = LinkState.Offline
                // Idle means "no socket yet"; keep whatever the open attempt published.
                ConnectionState.Idle -> Unit
            }
        }

    /** A caller releases its reservation; only the last release can start the idle timeout. */
    fun release(hostId: String) {
        synchronized(hosts) {
            val count = (retained[hostId] ?: 0) - 1
            if (count > 0) {
                retained[hostId] = count
                return
            }
            retained.remove(hostId)
            val host = hosts[hostId] ?: return
            synchronized(host) {
                if (!host.owned || host.idle != null || host.client == null) return
                host.idle = scope.launch {
                    delay(idleTimeoutMs)
                    synchronized(hosts) { if (hostId !in retained) close(hostId) }
                }
            }
        }
    }

    /** Closes the link immediately, for a host switch or shutdown. */
    fun close(hostId: String) {
        val host = hostOrNull(hostId) ?: return
        var owned = true
        val client =
            synchronized(host) {
                host.revision++
                host.idle?.cancel()
                host.idle = null
                host.watcher?.cancel()
                host.watcher = null
                owned = host.owned
                host.state.value = LinkState.Offline
                host.client.also { host.client = null }
            }
        if (client != null && owned) client.close()
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }
}

/**
 * An endpoint that knows why it failed; the connection owner publishes the code as
 * [LinkState.Failed].
 */
class LinkFailure(val code: String) : Exception(code)

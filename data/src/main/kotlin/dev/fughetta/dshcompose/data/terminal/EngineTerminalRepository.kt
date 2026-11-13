package dev.fughetta.dshcompose.data.terminal

import dev.fughetta.dshcompose.data.asOperationFailure
import dev.fughetta.dshcompose.data.link.requireGeneration
import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.terminal.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Terminal requests and domain handles for one engine connection. */
class EngineTerminalRepository(
    private val client: ProtocolClient,
    private val connectionActive: () -> Boolean = { true },
) : TerminalRepository, AutoCloseable {
    private val connectionGeneration = client.mux.state.value.generation
    @Volatile private var closed = false
    private val retentions = mutableMapOf<CoroutineScope, TerminalRetention>()

    private fun active() = !closed && connectionActive()

    override fun retain(
        scope: CoroutineScope,
        sessionId: String,
        id: String,
        current: () -> Boolean,
        onFailure: (Exception) -> Unit,
    ) {
        synchronized(retentions) {
            if (closed) {
                if (current()) onFailure(ConnectionClosedException())
                return
            }
            retentions
                .getOrPut(scope) { TerminalRetention(scope) }
                .hold(id, { retentionStream(sessionId, id) }, current, onFailure)
        }
    }

    override suspend fun awaitRetained(scope: CoroutineScope, id: String) {
        val retention =
            synchronized(retentions) { retentions[scope] } ?: throw ConnectionClosedException()
        retention.await(id)
    }

    override fun keepRetained(scope: CoroutineScope, ids: Set<String>) {
        synchronized(retentions) { retentions[scope]?.keep(ids) }
    }

    override fun releaseRetained(scope: CoroutineScope, id: String) {
        synchronized(retentions) { retentions[scope]?.release(id) }
    }

    override fun releaseRetentions(scope: CoroutineScope) {
        synchronized(retentions) { retentions.remove(scope)?.close() }
    }

    override fun close() {
        synchronized(retentions) {
            closed = true
            retentions.values.forEach { it.close() }
            retentions.clear()
        }
    }

    override val link: Flow<LinkSnapshot>
        get() =
            client.mux.state.map {
                LinkSnapshot(
                    active() &&
                        it.state == ConnectionState.Connected &&
                        it.generation == connectionGeneration,
                    connectionGeneration?.number,
                )
            }

    override val supports
        get() = client.connection.supportsTerminals

    override suspend fun inventory(sessionId: String): TerminalInventory =
        readTerminalInventory(this, sessionId)

    override suspend fun environment(sessionId: String): TerminalEnvironment =
        client.withGeneration(connectionGeneration, ::active) {
            val raw =
                client.connection
                    .call("terminal/environment", buildJsonObject { put("agentId", sessionId) })
                    .objectValue()
            fun positive(key: String, default: Int) =
                raw.long(key)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: default
            return@withGeneration TerminalEnvironment(
                raw.string("cwd").orEmpty(),
                positive("maxInputBytes", 65536),
                positive("maxBufferedBytes", 2097152),
                positive("maxCols", 500),
                positive("maxRows", 200),
                positive("scrollback", 1000),
            )
        }

    override suspend fun shells(sessionId: String): PersistentList<TerminalShell> =
        client.withGeneration(connectionGeneration, ::active) {
            (client.connection.call(
                    "terminal/shells",
                    buildJsonObject { put("agentId", sessionId) },
                ) as? JsonArray ?: throw HostProtocolException("Missing terminal shells"))
                .map { terminalShell(it.objectValue()) }
                .toPersistentList()
        }

    override suspend fun list(sessionId: String): PersistentList<TerminalInfo> =
        client.withGeneration(connectionGeneration, ::active) {
            (client.connection.call(
                    "terminal/list",
                    buildJsonObject { put("sessionId", sessionId) },
                ) as? JsonArray ?: throw HostProtocolException("Missing terminals"))
                .map { terminalInfo(it.objectValue()) }
                .toPersistentList()
        }

    override suspend fun create(
        sessionId: String,
        id: String,
        cols: Int,
        rows: Int,
        shellPath: String?,
    ): TerminalInfo =
        client.withGeneration(connectionGeneration, ::active) {
            terminalInfo(
                client.connection
                    .call(
                        "terminal/create",
                        buildJsonObject {
                            put("agentId", sessionId)
                            putJsonObject("request") {
                                put("id", id)
                                put("cols", cols)
                                put("rows", rows)
                                shellPath?.let { put("shellPath", it) }
                            }
                        },
                    )
                    .objectValue(),
            )
        }

    override suspend fun rename(sessionId: String, id: String, title: String): Unit =
        client.withGeneration(connectionGeneration, ::active) {
            client.connection.call(
                "terminal/rename",
                buildJsonObject {
                    put("agentId", sessionId)
                    put("id", id)
                    put("title", title)
                },
            )
            Unit
        }

    override suspend fun close(sessionId: String, id: String): Unit =
        client.withGeneration(connectionGeneration, ::active) {
            client.connection.call(
                "terminal/close",
                buildJsonObject {
                    put("agentId", sessionId)
                    put("id", id)
                },
            )
            Unit
        }

    private fun retentionStream(sessionId: String, id: String): Flow<Unit> = flow {
        client.requireGeneration(connectionGeneration, ::active)
        emitAll(
            client.mux
                .stream(
                    "terminal/retain",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("id", id)
                    },
                )
                .transform { event ->
                    client.requireGeneration(connectionGeneration, ::active)
                    if (
                        event is MuxEvent.Item &&
                            event.value.objectValue().string("type") == "retained"
                    )
                        emit(Unit)
                },
        )
    }
        .catch { throw it.asOperationFailure() }

    override fun attach(
        scope: CoroutineScope,
        sessionId: String,
        id: String,
        environment: TerminalEnvironment,
    ): TerminalSessionHandle {
        client.requireGeneration(connectionGeneration, ::active)
        if (!supports) throw dev.fughetta.dshcompose.domain.OperationFailure("terminal/unsupported")
        val link = TerminalAttachment(scope, client.connection, sessionId, id, environment)
        return TerminalSessionHandle(
            frames = link.frames.catch { throw it.asOperationFailure() },
            isWritable = {
                active() &&
                    client.mux.state.value.generation == connectionGeneration &&
                    link.writable
            },
            writeText = { text ->
                active() &&
                    client.mux.state.value.generation == connectionGeneration &&
                    link.write(text)
            },
            resizeTerminal = { cols, rows ->
                active() &&
                    client.mux.state.value.generation == connectionGeneration &&
                    link.resize(cols, rows)
            },
            release = link::close,
        )
    }
}

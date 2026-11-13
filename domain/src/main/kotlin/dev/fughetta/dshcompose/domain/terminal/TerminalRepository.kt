package dev.fughetta.dshcompose.domain.terminal

import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** One attached terminal shell: frames in, input/size out. */
class TerminalSessionHandle(
    val frames: Flow<TerminalFrame>,
    private val isWritable: () -> Boolean,
    private val writeText: (String) -> Boolean,
    private val resizeTerminal: (Int, Int) -> Boolean,
    private val release: () -> Unit,
) : AutoCloseable {
    val writable: Boolean
        get() = isWritable()

    fun write(text: String): Boolean = writeText(text)

    fun resize(cols: Int, rows: Int): Boolean = resizeTerminal(cols, rows)

    override fun close() = release()
}

/** Terminal inventory and attachment for one host. */
/** Connection snapshot the terminal screen uses to reload on reconnect. */
data class LinkSnapshot(val connected: Boolean, val generation: Long?)

interface TerminalRepository {
    val supports: Boolean

    val link: Flow<LinkSnapshot>

    suspend fun inventory(sessionId: String): TerminalInventory

    suspend fun environment(sessionId: String): TerminalEnvironment

    suspend fun shells(sessionId: String): PersistentList<TerminalShell>

    suspend fun list(sessionId: String): PersistentList<TerminalInfo>

    fun retain(
        scope: CoroutineScope,
        sessionId: String,
        id: String,
        current: () -> Boolean,
        onFailure: (Exception) -> Unit,
    )

    suspend fun awaitRetained(scope: CoroutineScope, id: String)

    fun keepRetained(scope: CoroutineScope, ids: Set<String>)

    fun releaseRetained(scope: CoroutineScope, id: String)

    fun releaseRetentions(scope: CoroutineScope)

    fun attach(
        scope: CoroutineScope,
        sessionId: String,
        id: String,
        environment: TerminalEnvironment,
    ): TerminalSessionHandle

    suspend fun create(
        sessionId: String,
        id: String,
        cols: Int,
        rows: Int,
        shellPath: String?,
    ): TerminalInfo

    suspend fun close(sessionId: String, id: String)

    suspend fun rename(sessionId: String, id: String, title: String)
}

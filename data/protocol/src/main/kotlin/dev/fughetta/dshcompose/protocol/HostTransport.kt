package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.workspaces.WorkspaceByteWindow
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Host operations whose HTTP attachments and remote encodings differ. Callers share the same client
 * API.
 */
interface HostTransport : Closeable {
    val connectTimeoutMillis: Long
        get() = 15000

    suspend fun call(endpoint: String, args: JsonObject = JsonObject(emptyMap())): JsonElement

    /** Requests such as compaction execute for longer than ordinary RPC reads. */
    suspend fun callWithTimeout(
        endpoint: String,
        args: JsonObject,
        timeoutMillis: Long,
    ): JsonElement = call(endpoint, args)

    suspend fun openMuxLink(): MultiplexerLink

    val supportsTerminals: Boolean
        get() = false

    val supportsScheduleManagement: Boolean
        get() = true

    val supportsGoalRead: Boolean
        get() = true

    val supportsJobStop: Boolean
        get() = true

    val supportsBundleRemoval: Boolean
        get() = true

    suspend fun readWorkspaceBytes(
        sessionId: String,
        path: String,
        offset: Long,
        length: Int,
    ): WorkspaceByteWindow

    suspend fun renderOfficePdf(
        sessionId: String,
        path: String,
        destination: OutputStream,
        maximumBytes: Long,
    ): OfficePdfResult

    suspend fun uploadFile(
        sessionId: String,
        file: File,
        name: String,
        onProgress: suspend (Long, Long?) -> Unit,
    ): FileReceipt

    suspend fun deleteSessionPermanently(sessionId: String)

    /** False when the carrier cannot stream session archives. */
    val supportsSessionExport: Boolean
        get() = false

    suspend fun copySessionArchive(
        sessionId: String,
        destination: OutputStream,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): Long = throw HostRemoteException("session-export/unsupported")
}

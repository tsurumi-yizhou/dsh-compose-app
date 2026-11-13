package dev.fughetta.dshcompose.data.workspace

import dev.fughetta.dshcompose.data.attachments.AttachmentMetadata
import dev.fughetta.dshcompose.data.link.requireGeneration
import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.data.outputStream
import dev.fughetta.dshcompose.data.transcript.*
import dev.fughetta.dshcompose.data.workspaces.*
import dev.fughetta.dshcompose.domain.references.FileReferenceCandidate
import dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate
import dev.fughetta.dshcompose.domain.workspaces.*
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** WorkspaceFilesRepository backed by one engine connection. */
class EngineWorkspaceFilesRepository(
    private val client: ProtocolClient,
    private val connectionActive: () -> Boolean = { true },
) : WorkspaceFilesRepository {
    override fun mediaType(given: String?, name: String): String =
        AttachmentMetadata.mimeOf(given, name)

    override suspend fun withArtifactFile(
        session: String,
        path: String,
        name: String,
        cache: String,
        existing: String?,
        progress: suspend (Long, Long?) -> Unit,
        consume: suspend (String) -> Boolean,
    ) =
        withArtifactFile(
            this,
            session,
            path,
            name,
            java.io.File(cache),
            existing?.let { java.io.File(it) },
            progress,
        ) {
            consume(it.absolutePath)
        }

    override suspend fun <T> readArtifactTable(
        session: String,
        path: String,
        cache: String,
        delimited: (ByteArray, Boolean, Boolean) -> T,
        workbook: (String) -> T,
    ): dev.fughetta.dshcompose.domain.transcript.ArtifactTable<T> =
        readArtifactTable(this, session, path, java.io.File(cache), delimited) {
            workbook(it.absolutePath)
        }

    override suspend fun referenceMatches(
        session: String,
        query: String,
        conversations: Boolean,
        immediate: Boolean,
    ): WorkspaceReferenceMatches =
        searchWorkspaceReferences(this, session, query, conversations, immediate)

    override suspend fun preview(
        directory: String?,
        session: String,
        path: String,
        renderPdf: Boolean,
        maximumBytes: Long,
    ): CachedWorkspacePreview =
        loadWorkspacePreview(
            directory?.let { java.io.File(it) },
            this,
            session,
            path,
            renderPdf,
            maximumBytes,
        )

    override suspend fun resolveReplyPath(session: String, path: String): String? =
        resolveReplyFile(this, session, path)

    private val connectionGeneration = client.mux.state.value.generation
    override val generation: Any?
        get() = connectionGeneration

    override suspend fun search(
        sessionId: String,
        query: String,
    ): PersistentList<FileReferenceCandidate> =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                val value =
                    client.connection.call(
                        "fileReferences/list",
                        buildJsonObject {
                            put("agentId", sessionId)
                            put("query", query)
                        },
                    )
                // A row the engine could not describe is skipped, not the whole listing.
                (value as? JsonArray
                        ?: throw HostProtocolException("Missing file reference results"))
                    .mapNotNull { raw ->
                        val row = raw as? JsonObject ?: return@mapNotNull null
                        FileReferenceCandidate(
                            row.string("path")?.takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null,
                            row.string("kind") == "directory",
                        )
                    }
                    .distinctBy { it.path }
                    .toPersistentList()
            }
        }

    override fun changes(sessionId: String, path: String): Flow<WorkspaceFileChange> = flow {
        client.requireGeneration(connectionGeneration, connectionActive)
        client.mux
            .stream(
                "workspaceFiles/changes",
                buildJsonObject {
                    put("workspaceFileScopeId", sessionId)
                    put("path", path.ifEmpty { "." })
                },
            )
            .collect { event ->
                client.requireGeneration(connectionGeneration, connectionActive)
                if (event is MuxEvent.Item) {
                    val value = event.value.objectValue()
                    when (value.string("kind")) {
                        "ready" -> emit(WorkspaceFileChange.Ready)
                        "change" -> {
                            val change = value["change"]!!.objectValue()
                            emit(
                                WorkspaceFileChange.Changed(
                                    change.string("version"),
                                    change.boolean("absent") == true,
                                ),
                            )
                        }
                    }
                }
            }
    }
        .flowOn(Dispatchers.Default)

    override suspend fun listing(
        sessionId: String,
        path: String,
    ): WorkspaceFileListing =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                val value =
                    client.connection
                        .call(
                            "workspaceFiles/list",
                            buildJsonObject {
                                put("workspaceFileScopeId", sessionId)
                                put("path", path.ifEmpty { "." })
                            },
                        )
                        .objectValue()
                val entries =
                    (value["entries"] as? JsonArray)
                        .orEmpty()
                        .map { raw ->
                            raw.objectValue().let {
                                WorkspaceFileEntry(
                                    it.requiredString("name"),
                                    it.requiredString("type"),
                                    it.long("size"),
                                )
                            }
                        }
                        .sortedWith { a, b ->
                            val directory = (a.type != "directory").compareTo(b.type != "directory")
                            if (directory != 0) directory
                            else compareWorkspaceFileNames(a.name, b.name)
                        }
                        .toPersistentList()
                WorkspaceFileListing(
                    value.string("path") ?: path,
                    entries,
                    value.boolean("truncated") == true,
                )
            }
        }

    private fun compareWorkspaceFileNames(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i] in '0'..'9' && y[j] in '0'..'9') {
                var ei = i
                var ej = j
                while (ei < x.length && x[ei] in '0'..'9') ei++
                while (ej < y.length && y[ej] in '0'..'9') ej++
                val nx = x.substring(i, ei).trimStart('0').ifEmpty { "0" }
                val ny = y.substring(j, ej).trimStart('0').ifEmpty { "0" }
                val numeric =
                    if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
                if (numeric != 0) return numeric
                i = ei
                j = ej
            } else {
                if (x[i] != y[j]) return x[i].compareTo(y[j])
                i++
                j++
            }
        }
        val remaining = (x.length - i).compareTo(y.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }

    override suspend fun readText(
        sessionId: String,
        path: String,
        offset: Int,
    ): WorkspaceTextPage =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                require(offset >= 1)
                val value =
                    client.connection
                        .call(
                            "workspaceFiles/read",
                            buildJsonObject {
                                put("workspaceFileScopeId", sessionId)
                                put("path", path)
                                putJsonObject("range") {
                                    put("offset", offset)
                                    put("limit", 500)
                                }
                            },
                        )
                        .objectValue()
                val count = value.requiredLong("lines").toInt()
                if (count !in 0..500) throw HostProtocolException("Invalid text page length")
                WorkspaceTextPage(
                    value.string("version").orEmpty(),
                    (value.long("offset") ?: offset.toLong()).toInt(),
                    count,
                    value.boolean("eof") == true,
                    if (count == 0) persistentListOf()
                    else value.requiredString("text").split('\n').toPersistentList(),
                )
            }
        }

    override suspend fun browse(path: String?): DirectoryLevel =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                val value =
                    client.connection
                        .call(
                            "directoryPicker/list",
                            buildJsonObject { path?.let { put("path", it) } },
                        )
                        .objectValue()
                fun rows(key: String): PersistentList<DirectoryEntry> =
                    (value[key] as? JsonArray)
                        .orEmpty()
                        .map { raw ->
                            val row = raw.objectValue()
                            DirectoryEntry(
                                row.requiredString("name"),
                                row.requiredString("path"),
                                row.boolean("hidden") == true,
                            )
                        }
                        .toPersistentList()
                val actual = value.requiredString("path")
                val crumbs =
                    rows("crumbs").ifEmpty {
                        persistentListOf(DirectoryEntry(actual, actual, false))
                    }
                DirectoryLevel(
                    actual,
                    value.requiredString("home"),
                    crumbs,
                    rows("entries"),
                    value.boolean("truncated") == true,
                )
            }
        }

    override suspend fun createDirectory(parent: String, name: String): String =
        client.withGeneration(connectionGeneration, connectionActive) {
            require(
                name.isNotBlank() &&
                    name != "." &&
                    name != ".." &&
                    '/' !in name &&
                    '\u0000' !in name,
            )
            val result =
                client.connection.call(
                    "directoryPicker/createDirectory",
                    buildJsonObject {
                        put("path", parent)
                        put("name", name)
                    },
                )
            return@withGeneration (result as? JsonPrimitive)?.contentOrNull?.takeIf {
                it.isNotBlank()
            } ?: throw HostProtocolException("Missing created directory path")
        }

    override suspend fun sessionReferences(
        sessionId: String,
        query: String,
    ): PersistentList<SessionReferenceCandidate> =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                val value =
                    client.connection.call(
                        "sessionReferenceResolver/candidates",
                        buildJsonObject {
                            put("agentId", sessionId)
                            put("query", query)
                        },
                    )
                (value as? JsonArray ?: throw HostProtocolException("Missing session references"))
                    // A conversation without a usable mention cannot be inserted; skip it.
                    .mapNotNull { raw ->
                        val row = raw as? JsonObject ?: return@mapNotNull null
                        val id =
                            row.string("sessionId")?.takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null
                        SessionReferenceCandidate(
                            id,
                            row.string("displayTitle") ?: row.string("label") ?: id,
                            row.string("mention")?.takeIf { it.isNotEmpty() }
                                ?: return@mapNotNull null,
                            row.boolean("sameWorkspace") == true,
                            row.string("cwd"),
                        )
                    }
                    .toPersistentList()
            }
        }

    override suspend fun stat(sessionId: String, path: String): WorkspaceFileStat =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.Default) {
                val value =
                    client.connection
                        .call(
                            "workspaceFiles/stat",
                            buildJsonObject {
                                put("workspaceFileScopeId", sessionId)
                                put("path", path)
                            },
                        )
                        .objectValue()
                WorkspaceFileStat(
                    value.requiredString("absolutePath"),
                    value.requiredString("version"),
                    value.long("bytes"),
                )
            }
        }

    override suspend fun readBytes(
        sessionId: String,
        path: String,
        offset: Long,
        length: Int,
    ): WorkspaceByteWindow =
        client.withGeneration(connectionGeneration, connectionActive) {
            client.connection.readWorkspaceBytes(sessionId, path, offset, length)

            /**
             * Streams to a caller-owned destination; every window must belong to the stat's file
             * version.
             */
        }

    override suspend fun copy(
        sessionId: String,
        path: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        maximumBytes: Long,
        onProgress: suspend (Long, Long?) -> Unit,
    ): WorkspaceFileCopy =
        client.withGeneration(connectionGeneration, connectionActive) {
            withContext(Dispatchers.IO) {
                require(maximumBytes >= 0)
                val stat = stat(sessionId, path)
                val statBytes = stat.bytes
                if (statBytes != null && statBytes > maximumBytes)
                    throw HostRemoteException("workspace-file/too-large")
                var offset = 0L
                var eof = false
                onProgress(0, statBytes)
                while (!eof) {
                    currentCoroutineContext().ensureActive()
                    val remaining = maximumBytes - offset
                    val length =
                        if (remaining >= 1024 * 1024) 1024 * 1024 else (remaining + 1).toInt()
                    val window = readBytes(sessionId, path, offset, length)
                    if (window.version != stat.version)
                        throw HostRemoteException("workspace-file/changed")
                    if (window.offset != offset || window.data.isEmpty() && !window.eof)
                        throw HostProtocolException("Non-contiguous byte window")
                    if (window.data.size > remaining)
                        throw HostRemoteException("workspace-file/too-large")
                    destination(window.data, 0, window.data.size)
                    offset += window.data.size
                    onProgress(offset, stat.bytes)
                    eof = window.eof
                }
                if (stat.bytes != null && stat.bytes != offset)
                    throw HostRemoteException("workspace-file/changed")
                WorkspaceFileCopy(offset, stat.version)
            }
        }

    override suspend fun renderPdf(
        sessionId: String,
        path: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
    ): WorkspacePdf =
        client.withGeneration(connectionGeneration, connectionActive) {
            val result =
                client.connection.renderOfficePdf(
                    sessionId,
                    path,
                    destination.outputStream(),
                    64L * 1024 * 1024,
                )
            return@withGeneration WorkspacePdf(result.bytes, result.missingFonts)
        }
}

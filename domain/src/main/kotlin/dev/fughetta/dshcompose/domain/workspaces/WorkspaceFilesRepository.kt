package dev.fughetta.dshcompose.domain.workspaces

import dev.fughetta.dshcompose.domain.references.FileReferenceCandidate
import dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.Flow

/**
 * What the file browser needs from one host's engine: listings, text pages, binary copies and
 * change streams. Implementations live in the data layer; the UI never sees a protocol type.
 */
interface WorkspaceFilesRepository {
    fun mediaType(given: String?, name: String): String

    suspend fun withArtifactFile(
        session: String,
        path: String,
        name: String,
        cache: String,
        existing: String?,
        progress: suspend (Long, Long?) -> Unit,
        consume: suspend (String) -> Boolean,
    )

    suspend fun <T> readArtifactTable(
        session: String,
        path: String,
        cache: String,
        delimited: (ByteArray, Boolean, Boolean) -> T,
        workbook: (String) -> T,
    ): dev.fughetta.dshcompose.domain.transcript.ArtifactTable<T>

    suspend fun referenceMatches(
        session: String,
        query: String,
        conversations: Boolean,
        immediate: Boolean,
    ): WorkspaceReferenceMatches

    suspend fun preview(
        directory: String?,
        session: String,
        path: String,
        renderPdf: Boolean,
        maximumBytes: Long,
    ): CachedWorkspacePreview

    suspend fun resolveReplyPath(session: String, path: String): String?

    /** Opaque link token: changes when the engine connection is replaced. */
    val generation: Any?

    suspend fun search(sessionId: String, query: String): PersistentList<FileReferenceCandidate>

    suspend fun sessionReferences(
        sessionId: String,
        query: String,
    ): PersistentList<SessionReferenceCandidate>

    suspend fun listing(sessionId: String, path: String = ""): WorkspaceFileListing

    suspend fun stat(sessionId: String, path: String): WorkspaceFileStat

    suspend fun readText(sessionId: String, path: String, offset: Int = 1): WorkspaceTextPage

    /** One byte window of a binary workspace file, for in-app previews. */
    suspend fun readBytes(
        sessionId: String,
        path: String,
        offset: Long,
        length: Int = 1024 * 1024,
    ): WorkspaceByteWindow

    suspend fun copy(
        sessionId: String,
        path: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
        maximumBytes: Long = Long.MAX_VALUE,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): WorkspaceFileCopy

    /** Converts an office document to PDF on the host and streams it to [destination]. */
    suspend fun renderPdf(
        sessionId: String,
        path: String,
        destination: dev.fughetta.dshcompose.domain.ByteSink,
    ): WorkspacePdf

    fun changes(sessionId: String, path: String): Flow<WorkspaceFileChange>

    /** Directory levels for the chooser, independent of any conversation. */
    /** [path] null opens the host's home directory. */
    suspend fun browse(path: String? = null): DirectoryLevel

    suspend fun createDirectory(parent: String, name: String): String
}

/** Result of an office→PDF conversion: bytes written and the fonts the host could not find. */
data class WorkspacePdf(val bytes: Long, val missingFonts: PersistentList<String>)

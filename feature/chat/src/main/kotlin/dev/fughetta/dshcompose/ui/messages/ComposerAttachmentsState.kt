package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.domain.attachments.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.*

/** UI selection owns handles until a send hands them to the pending-message repository. */
class ComposerAttachmentsState(
    private val validate: ValidateAttachmentSelectionUseCase = ValidateAttachmentSelectionUseCase(),
) {
    private val guard = Any()
    private val mutable =
        MutableStateFlow<PersistentMap<String, PersistentList<ComposerFile>>>(persistentMapOf())
    val state = mutable.asStateFlow()

    fun selected(sessionId: String): PersistentList<ComposerFile> =
        mutable.value[sessionId] ?: persistentListOf()

    fun allFiles(): List<ComposerFile> = mutable.value.values.flatten()

    fun ensureCapacity(sessionId: String, incoming: List<ComposerFile>) {
        validate(selected(sessionId), incoming)
    }

    fun add(sessionId: String, incoming: List<ComposerFile>): PersistentList<ComposerFile> =
        synchronized(guard) {
            val current = selected(sessionId)
            if (incoming.isEmpty()) return@synchronized current
            validate(current, incoming)
            current.addAll(incoming).also { mutable.value = mutable.value.putting(sessionId, it) }
        }

    fun detach(sessionId: String, attachmentIds: Set<String>): List<ComposerFile> =
        synchronized(guard) {
            val current = selected(sessionId)
            val detached = current.filter { it.id in attachmentIds }
            val remaining = current.filterNot { it.id in attachmentIds }.toPersistentList()
            mutable.value =
                if (remaining.isEmpty()) mutable.value.removing(sessionId)
                else mutable.value.putting(sessionId, remaining)
            detached
        }

    fun take(sessionId: String): List<ComposerFile> =
        synchronized(guard) {
            val files = selected(sessionId)
            mutable.value = mutable.value.removing(sessionId)
            files
        }

    fun move(from: String, to: String) =
        synchronized(guard) {
            if (from == to) return@synchronized
            val files = selected(from)
            if (files.isEmpty()) return@synchronized
            val destination = selected(to)
            validate(destination, files)
            mutable.value = mutable.value.removing(from).putting(to, destination.addAll(files))
        }

    fun clear(): List<ComposerFile> =
        synchronized(guard) { allFiles().also { mutable.value = persistentMapOf() } }
}

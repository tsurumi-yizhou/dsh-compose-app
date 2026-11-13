package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.ProtocolClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** Publishes the selected connection's attachment operations to application consumers. */
class AttachmentConnections : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow<AttachmentsRepository?>(null)
    val connection = mutable.asStateFlow()
    private var currentOps: EngineAttachmentsRepository? = null

    fun connect(client: ProtocolClient, parent: CoroutineScope): AttachmentsRepository {
        val next = EngineAttachmentsRepository(client, parent)
        val previous =
            synchronized(lock) {
                currentOps.also {
                    currentOps = next
                    mutable.value = next
                }
            }
        previous?.close()
        return next
    }

    fun disconnect(expected: AttachmentsRepository) {
        val previous =
            synchronized(lock) {
                if (currentOps !== expected) return
                currentOps.also {
                    currentOps = null
                    mutable.value = null
                }
            }
        previous?.close()
    }

    override fun close() {
        val previous =
            synchronized(lock) {
                currentOps.also {
                    currentOps = null
                    mutable.value = null
                }
            }
        previous?.close()
    }
}

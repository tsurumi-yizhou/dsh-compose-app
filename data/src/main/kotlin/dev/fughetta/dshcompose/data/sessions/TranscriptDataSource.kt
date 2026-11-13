package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.flow.Flow

/** The two transcript origins share one reducer and pagination owner. */
sealed class TranscriptDataSource {
    abstract val identity: Any
    abstract val generation: ConnectionGeneration?

    abstract fun watch(): Flow<SessionUpdate>

    abstract suspend fun history(throughSeq: Long, beforeSeq: Long?): HistoryPage

    abstract suspend fun activate(generation: ConnectionGeneration)
}

internal class ClientTranscriptDataSource(
    private val client: ProtocolClient,
    private val sessionId: String,
) : TranscriptDataSource() {
    override val identity: Any
        get() = client

    override val generation
        get() = client.mux.state.value.generation

    override fun watch() = client.watchSession(sessionId)

    override suspend fun history(throughSeq: Long, beforeSeq: Long?) =
        client.history(sessionId, throughSeq, beforeSeq)

    override suspend fun activate(generation: ConnectionGeneration) {
        client.activateForInteraction(sessionId, generation)
    }
}

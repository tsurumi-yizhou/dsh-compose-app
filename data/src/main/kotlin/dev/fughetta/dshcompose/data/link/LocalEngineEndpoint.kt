package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.protocol.HostConnection
import dev.fughetta.dshcompose.protocol.ProtocolClient

/**
 * The local engine: one HTTP connection, a login with the runtime token, then the mux handshake.
 * [identity] is read lazily so a restarted engine (new port or token) opens a fresh link.
 */
class LocalEngineEndpoint(private val identity: suspend () -> Pair<String, String>?) {
    suspend fun open(): ProtocolClient {
        val (url, token) = identity() ?: throw LinkFailure("link/engine-stopped")
        val connection = HostConnection(url)
        val client = ProtocolClient(connection)
        return try {
            connection.login(token)
            client.mux.ready()
            client
        } catch (error: Exception) {
            client.close()
            throw error
        }
    }
}

package dev.fughetta.dshcompose.data.hosts

import dev.fughetta.dshcompose.data.link.HostConnections
import dev.fughetta.dshcompose.data.link.LinkFailure
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ServiceRemovalTest {
    @Test
    fun unreachableServiceRequiresExplicitLocalErasureInsteadOfPretendingRevocationSucceeded() =
        runTest {
            val host = RemoteHost("compose-away", "Away", "https://away.example")
            var saved = listOf(host)
            var attempts = 0
            val connections = HostConnections(backgroundScope)
            connections.register(host.id) {
                attempts++
                throw LinkFailure("service/unreachable")
            }
            val repository =
                EngineHostsRepository(
                    connections,
                    readServices = { saved },
                    writeServices = { hosts, _, _ -> saved = hosts },
                )
            try {
                try {
                    repository.removeService(host.id)
                    fail("Revocation requires a response from the service")
                } catch (expected: LinkFailure) {
                    assertEquals("service/unreachable", expected.code)
                }
                assertEquals(listOf(host), saved)
                assertEquals(1, attempts)
                repository.forgetService(host.id)
                assertTrue(saved.isEmpty())
                assertEquals(1, attempts)
            } finally {
                repository.close()
            }
        }
}

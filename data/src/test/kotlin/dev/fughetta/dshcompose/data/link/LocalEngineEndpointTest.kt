package dev.fughetta.dshcompose.data.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalEngineEndpointTest {
    @Test
    fun aStoppedEngineReportsItsOwnFailureCode() = runTest {
        val endpoint = LocalEngineEndpoint { null }

        val error = runCatching { endpoint.open() }.exceptionOrNull()

        assertEquals("link/engine-stopped", (error as LinkFailure).code)
    }

    @Test
    fun aRejectedLoginFailsTheOpen() = runTest {
        val endpoint = LocalEngineEndpoint { "http://127.0.0.1:9" to "bad-token" }

        val error = runCatching { endpoint.open() }.exceptionOrNull()

        assertNotNull(error)
        assertNotEquals("link/engine-stopped", (error as? LinkFailure)?.code)
    }
}

package dev.fughetta.dshcompose.data.usage

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.usage.UsageChange
import dev.fughetta.dshcompose.domain.usage.UsageRepository
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineUsageRepositoryTest {
    @Test
    fun replacedConnectionCannotOverwriteFollowedSnapshotAndResetClearsUsage() = runTest {
        val late = CompletableDeferred<ToolPayload>()
        val old =
            object : UsageRepository by EngineUsageRepository(backgroundScope, {}) {
                override val generation = ConnectionGeneration(1, "test", "/home")

                override suspend fun projections(sessionId: String) =
                    withContext(NonCancellable) { late.await() }
            }
        var reads = 0
        val current =
            object : UsageRepository by EngineUsageRepository(backgroundScope, {}) {
                override val generation = ConnectionGeneration(1, "test", "/home")

                override suspend fun projections(sessionId: String): ToolPayload {
                    reads++
                    return projections(1, 4).toToolPayload()
                }
            }
        val monitor = EngineUsageRepository(backgroundScope) {}
        try {
            monitor.connect(old, "host", "session")
            runCurrent()
            monitor.connect(current, "host", "session")
            runCurrent()
            assertEquals(4L, monitor.state.value.usage.output)
            late.complete(projections(10, 99).toToolPayload())
            runCurrent()
            assertEquals(4L, monitor.state.value.usage.output)
            monitor.sessionUpdate(UsageChange.Snapshot(projections(2, 9).toToolPayload()))
            assertEquals(9L, monitor.state.value.usage.output)
            monitor.sessionUpdate(UsageChange.Reset)
            assertEquals(0L, monitor.state.value.usage.output)
            assertEquals("Reuse the selected-session follow without another seed read", 1, reads)
        } finally {
            late.complete(projections(10, 99).toToolPayload())
            monitor.close()
        }
    }

    private fun projections(sequence: Long, output: Long) = buildJsonObject {
        put("asOfSeq", sequence)
        putJsonObject("values") { putJsonObject("tokenUsage") { put("outputTokens", output) } }
    }
}

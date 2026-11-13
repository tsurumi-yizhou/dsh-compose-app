package dev.fughetta.dshcompose.data.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalRetentionTest {
    @Test
    fun retiringPendingAttachmentsCannotAcknowledgeAReusedTerminalId() = runTest {
        val old = Channel<Unit>(Channel.UNLIMITED)
        val next = Channel<Unit>(Channel.UNLIMITED)
        val retention = TerminalRetention(backgroundScope)
        try {
            retention.hold("shell", { old.receiveAsFlow() }, { true }) { throw it }
            val waiting = backgroundScope.async { retention.await("shell") }
            runCurrent()
            retention.close()
            runCurrent()
            assertTrue(waiting.isCancelled)
            retention.hold("shell", { next.receiveAsFlow() }, { true }) { throw it }
            val attached = backgroundScope.async { retention.await("shell") }
            runCurrent()
            old.send(Unit)
            runCurrent()
            assertFalse(attached.isCompleted)
            next.send(Unit)
            runCurrent()
            attached.await()
        } finally {
            retention.close()
            old.close()
            next.close()
        }
    }
}

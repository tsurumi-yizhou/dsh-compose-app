package dev.fughetta.dshcompose.ui.reasoning

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningTranslationStateTest {
    @Test
    fun completedFragmentsTranslateOnceWhileTheStreamingTailStaysOriginal() = runTest {
        val answers = Channel<String>(Channel.UNLIMITED)
        val requests = mutableListOf<String>()
        val state =
            ReasoningTranslationState(backgroundScope, StandardTestDispatcher(testScheduler)) {
                requests += it
                answers.receive()
            }
        state.update("First\n\nTail", true, 100)
        state.enable()
        runCurrent()
        assertEquals(listOf("First"), requests)
        assertTrue(state.uiState.value.pending)
        answers.send("Translated first")
        runCurrent()
        assertEquals("Translated first\n\nTail", state.uiState.value.text)
        assertFalse(state.uiState.value.pending)
        state.update("First\n\nTail", false, 100)
        runCurrent()
        assertEquals(listOf("First", "Tail"), requests)
        answers.send("Translated tail")
        runCurrent()
        assertEquals("Translated first\n\nTranslated tail", state.uiState.value.text)
        state.close()
        assertEquals(ReasoningTranslationUiState(), state.uiState.value)
    }

    @Test
    fun retryKeepsBlockStateAndDisposalRejectsALateTranslation() = runTest {
        val finish = CompletableDeferred<String>()
        var attempts = 0
        val state =
            ReasoningTranslationState(backgroundScope, StandardTestDispatcher(testScheduler)) {
                if (++attempts == 1) throw IllegalStateException("unavailable")
                withContext(NonCancellable) { finish.await() }
            }
        state.update("Original", false, 100)
        state.enable()
        runCurrent()
        assertTrue(state.uiState.value.failed)
        assertFalse(state.uiState.value.pending)
        assertEquals("Original", state.uiState.value.text)
        state.retry()
        runCurrent()
        assertTrue(state.uiState.value.pending)
        assertFalse(state.uiState.value.failed)
        state.close()
        finish.complete("Late result")
        runCurrent()
        assertEquals(ReasoningTranslationUiState(), state.uiState.value)
        assertEquals(2, attempts)
    }
}

package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.domain.drafts.*
import dev.fughetta.dshcompose.ui.composer.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerStateTest {
    private class Drafts(initial: DraftsState = DraftsState(loaded = true)) : DraftsRepository {
        override val state = MutableStateFlow(initial)
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun save(sessionId: String, text: String) {
            writes.add(sessionId to text)
            state.value = state.value.copy(values = state.value.values + (sessionId to text))
        }

        override suspend fun forget(sessionId: String) {
            writes.add(sessionId to "")
            state.value = state.value.copy(values = state.value.values - sessionId)
        }
    }

    @Test
    fun typingPersistsTextWhileSelectionChangesAndAcknowledgementPreserveLaterEdits() = runTest {
        val repository = Drafts(DraftsState(mapOf("session" to "submitted"), loaded = true))
        val composer = ComposerState(repository, backgroundScope)
        runCurrent()
        composer.edit("session") {
            it.copy(quote = "quoted", draftCursor = 3, draftSelectionEnd = 4)
        }
        assertTrue(repository.writes.isEmpty())
        val span = ReferenceSpan(0, 3, "new", ReferenceSpanKind.File)
        composer.edit("session") {
            it.copy(
                draft = "new text",
                draftCursor = 5,
                draftSelectionEnd = 6,
                draftSpans = persistentListOf(span),
            )
        }
        composer.sent("session", "submitted", "quoted")
        val current = composer.input("session")
        assertEquals("new text", current.draft)
        assertEquals(5, current.draftCursor)
        assertEquals(6, current.draftSelectionEnd)
        assertEquals(listOf(span), current.draftSpans)
        assertNull(current.quote)
        assertEquals(listOf("session" to "new text"), repository.writes)
        composer.sent("session", "new text", null)
        assertEquals(ComposerInput(), composer.input("session"))
        assertEquals("session" to "", repository.writes.last())
    }

    @Test
    fun lateStorageRestorationCannotReplaceAnExplicitClearOrDeletedConversation() = runTest {
        val repository = Drafts(DraftsState())
        val composer = ComposerState(repository, backgroundScope)
        composer.edit("cleared", replaceText = true) { it.copy(draft = "") }
        composer.forget("deleted")
        repository.state.value =
            DraftsState(
                mapOf("cleared" to "obsolete", "deleted" to "removed", "untouched" to "restored"),
                loaded = true,
            )
        runCurrent()
        assertEquals("", composer.input("cleared").draft)
        assertEquals("", composer.input("deleted").draft)
        assertEquals("restored", composer.input("untouched").draft)
        assertEquals(listOf("cleared" to "", "deleted" to ""), repository.writes)
    }

    @Test
    fun acknowledgedCreationTransfersTheWholeEditorAndHostSwitchResetsOnlyTransientState() =
        runTest {
            val repository = Drafts()
            val composer = ComposerState(repository, backgroundScope)
            val span = ReferenceSpan(0, 4, "file", ReferenceSpanKind.File)
            composer.edit("draft") {
                it.copy(
                    draft = "file prompt",
                    quote = "quote",
                    draftCursor = 4,
                    draftSpans = persistentListOf(span),
                )
            }
            val original = composer.input("draft")
            assertEquals(original, composer.promote("draft", "created"))
            assertEquals(original, composer.input("created"))
            assertEquals(ComposerInput(), composer.input("draft"))
            assertEquals("file prompt", repository.state.value.values["created"])
            assertFalse(repository.state.value.values.containsKey("draft"))
            composer.resetTransient()
            assertEquals(ComposerInput(draft = "file prompt"), composer.input("created"))
            repository.state.value = repository.state.value.copy(storageErrorRevision = 1)
            runCurrent()
            assertEquals(1L, composer.state.value.storageErrorRevision)
        }
}

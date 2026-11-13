package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.domain.references.SessionReferenceCandidate
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository
import dev.fughetta.dshcompose.ui.DRAFT_SESSION_ID
import dev.fughetta.dshcompose.ui.ReferencePickerState
import dev.fughetta.dshcompose.ui.composer.ReferenceInsertion
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReferencesViewModelTest {
    @Test
    fun draftTypingFiltersCandidatesAndCommitsTheActiveMentionWithoutServerSession() = runTest {
        val generation = Any()
        val repository =
            Proxy.newProxyInstance(
                WorkspaceFilesRepository::class.java.classLoader,
                arrayOf(WorkspaceFilesRepository::class.java),
            ) { _, method, _ ->
                if (method.name == "getGeneration") generation
                else error("New drafts must not query a nonexistent server session: ${method.name}")
            } as WorkspaceFilesRepository
        var shown: ReferencePickerState? = null
        var selected: Pair<ReferencePickerState, ReferenceInsertion>? = null
        val candidate =
            SessionReferenceCandidate("task_1", "任务", "@[任务](dsh-session:task_1)", false, null)
        val model =
            ReferencesViewModel(
                this,
                message = { it.toString() },
                publish = { shown = it },
                insert = { picker, insertion ->
                    selected = picker to insertion
                    true
                },
                draftCandidates = { query ->
                    listOf(candidate).filter { it.title.contains(query) }
                },
            )
        model.connect(repository)
        model.composer(DRAFT_SESSION_ID, "查看 @", 4, 4, false)
        assertTrue(shown!!.inline)
        assertEquals(listOf(candidate), shown!!.sessions)
        model.composer(DRAFT_SESSION_ID, "查看 @任务", 6, 6, false)
        model.chooseSession("task_1")
        assertEquals(3, selected!!.first.start)
        assertEquals(6, selected!!.first.end)
        assertEquals(candidate.mention, selected!!.second.text)
        assertNull(shown)
        model.composer(DRAFT_SESSION_ID, "@不存在", 4, 4, false)
        assertTrue(shown!!.sessions.isEmpty())
        model.composer(DRAFT_SESSION_ID, "", 0, 0, false)
        assertNull(shown)
        model.close()
    }
}

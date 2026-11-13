package dev.fughetta.dshcompose.data.drafts

import dev.fughetta.dshcompose.data.database.DraftRow
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDraftsRepositoryTest {
    @Test
    fun editsAndDeletionDuringRestoreWinAndTypingWritesAreCoalesced() = runTest {
        val loaded = CompletableDeferred<List<DraftRow>>()
        val writes = mutableListOf<DraftRow>()
        val repository =
            RoomDraftsRepository(
                load = { loaded.await() },
                write = { row -> writes.add(row) },
                applicationScope = backgroundScope,
                now = { 50 },
            )
        runCurrent()
        repository.save("editing", "first")
        repository.save("editing", "latest")
        repository.forget("deleted")
        loaded.complete(
            listOf(
                DraftRow("editing", "old", 10),
                DraftRow("deleted", "old", 10),
                DraftRow("untouched", "restored", 10),
            ),
        )
        runCurrent()
        assertTrue(repository.state.value.loaded)
        assertEquals(
            mapOf("editing" to "latest", "untouched" to "restored"),
            repository.state.value.values,
        )
        advanceTimeBy(400)
        runCurrent()
        assertEquals(
            listOf("editing" to "latest", "deleted" to ""),
            writes.map { it.session to it.text },
        )
    }

    @Test
    fun failedWriteIsReportedAndLaterEditsStillPersist() = runTest {
        var fail = true
        val writes = mutableListOf<DraftRow>()
        val repository =
            RoomDraftsRepository(
                load = { emptyList<DraftRow>() },
                write = { row ->
                    if (fail) throw IOException("disk failure")
                    writes.add(row)
                },
                applicationScope = backgroundScope,
                now = { 50 },
            )
        runCurrent()
        repository.save("session", "unsaved")
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1L, repository.state.value.storageErrorRevision)
        assertEquals("unsaved", repository.state.value.values["session"])
        assertTrue(writes.isEmpty())
        fail = false
        repository.forget("session")
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals("", writes.single().text)
        assertFalse(repository.state.value.values.containsKey("session"))
    }
}

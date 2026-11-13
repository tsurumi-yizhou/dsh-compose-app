package dev.fughetta.dshcompose.data.sessions.search

import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineSessionSearchRepositoryTest {
    @Test
    fun queryAndArchiveChangesShareTheIndexWhileInventoryChangesReplaceItsProjection() = runTest {
        val repository =
            EngineSessionSearchRepository(backgroundScope, StandardTestDispatcher(testScheduler))
        val rows =
            persistentListOf(
                SessionSummary(
                    "one",
                    "Alpha project",
                    "/work",
                    false,
                    false,
                    true,
                    null,
                    null,
                    1,
                ),
            )
        try {
            repository.inventory(rows, emptySet())
            val first = repository.results("alpha", emptyList()).active.single().session
            repository.inventory(rows, setOf("one"))
            val archived = repository.results("project", emptyList())
            assertTrue(archived.active.isEmpty())
            assertSame(first, archived.archived.single().session)
            repository.inventory(
                rows.replacingAt(0, rows[0].copy(title = "Beta project")),
                emptySet(),
            )
            assertTrue(repository.results("alpha", emptyList()).isEmpty)
            val updated = repository.results("beta", emptyList()).active.single().session
            assertEquals("Beta project", updated.title)
            assertNotSame(first, updated)
            repository.close()
            assertTrue(repository.results("beta", emptyList()).isEmpty)
        } finally {
            repository.close()
        }
    }
}

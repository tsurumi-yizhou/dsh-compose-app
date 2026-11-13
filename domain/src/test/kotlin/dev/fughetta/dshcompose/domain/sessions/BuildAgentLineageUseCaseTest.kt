package dev.fughetta.dshcompose.domain.sessions

import kotlinx.collections.immutable.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BuildAgentLineageUseCaseTest {
    @Test
    fun liveCatalogAndSessionMetadataFormOneNavigableLineage() = runBlocking {
        fun row(
            id: String,
            parent: String? = null,
            metadata: SessionSubagents = SessionSubagents(),
            running: Boolean = false,
        ) =
            SessionSummary(
                id,
                "title-$id",
                null,
                running,
                false,
                true,
                parent,
                if (parent != null) "subagent" else null,
                0,
                metadata,
            )
        val selected =
            row(
                "selected",
                "lead",
                SessionSubagents(
                    catalog = persistentListOf(SubagentCatalogEntry("stale")),
                    identity = SubagentIdentity("Selected", "continuable"),
                ),
            )
        val sessions =
            listOf(
                selected,
                row(
                    "worker",
                    "selected",
                    SessionSubagents(
                        identity = SubagentIdentity("Identity", "continuable"),
                        timing = SubagentTiming(10, 20, 50, true),
                    ),
                    running = true,
                ),
                row(
                    "extra",
                    "selected",
                    SessionSubagents(timing = SubagentTiming(lastTurnCompleted = false)),
                ),
                row("external", "selected"),
                row("hidden", "external"),
            )
        val projection =
            SessionSubagents(
                catalog =
                    persistentListOf(
                        SubagentCatalogEntry("worker", "Live label", "continuable", 100),
                        SubagentCatalogEntry("external", "External", "external"),
                    ),
            )
        val result = BuildAgentLineageUseCase()(sessions, "selected", projection)
        assertEquals(
            listOf("worker", "external", "extra"),
            result.nodes.map { it.address.childSessionId },
        )
        assertEquals(SubagentAddress("lead", "selected", SubagentMode.Continuable), result.parent)
        val worker = result.nodes.first()
        assertEquals("Live label", worker.label)
        assertEquals("running", worker.status)
        assertEquals(40L, worker.elapsedMs)
        assertEquals(100L, worker.createdAt)
        assertEquals("failed", result.nodes.last().status)
        assertEquals(setOf("worker"), result.running)
    }
}

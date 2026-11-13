package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceEvent
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceRegistry
import dev.fughetta.dshcompose.domain.workspaces.workspaceSessions
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRegistryTest {
    @Test
    fun streamEditsAndAccessReplacementsWinOverLateMutationRepliesAndMembershipFiltersLargeLists() {
        val workspace =
            Workspace("w1", "/project", "Original", persistentSetOf("s2", "s3", "s9000"), null)
        val registry = WorkspaceRegistry()
        registry.apply(
            WorkspaceEvent.Snapshot(
                persistentListOf(workspace),
                persistentSetOf("s3"),
                persistentListOf("s9000"),
            ),
        )
        val pending = registry.beginMutation()
        registry.apply(
            WorkspaceEvent.Upsert(
                Workspace("w1", "/project", "Stream title", workspace.sessionIds, null),
            ),
        )
        registry.acknowledge(
            Workspace("w1", "/project", "Late reply", workspace.sessionIds, null),
            pending,
        )
        assertEquals("Stream title", registry.snapshot().single().title)
        val removal = registry.beginMutation()
        registry.apply(WorkspaceEvent.Remove("w1"))
        registry.acknowledge(workspace, removal)
        assertTrue(registry.snapshot().isEmpty())
        val beforeAccessChange = registry.beginMutation()
        registry.apply(
            WorkspaceEvent.Snapshot(persistentListOf(), persistentSetOf(), persistentListOf()),
        )
        registry.acknowledge(workspace, beforeAccessChange)
        assertTrue(registry.snapshot().isEmpty())
        registry.acknowledge(workspace, registry.beginMutation())
        assertEquals(workspace, registry.snapshot().single())
        val olderCollections = registry.beginMutation()
        registry.apply(WorkspaceEvent.Order(persistentListOf("w1")))
        registry.apply(WorkspaceEvent.Pinned(persistentListOf("new-pin")))
        registry.apply(WorkspaceEvent.Archived(persistentSetOf("new-archive")))
        registry.acknowledgeOrder(persistentListOf(), olderCollections)
        registry.acknowledgePinned(persistentListOf("old-pin"), olderCollections)
        registry.acknowledgeArchived(persistentSetOf("old-archive"), olderCollections)
        assertEquals(listOf("new-pin"), registry.pinned)
        assertEquals(setOf("new-archive"), registry.archived)
        val current = registry.beginMutation()
        registry.acknowledgePinned(persistentListOf("confirmed-pin"), current)
        registry.acknowledgeArchived(persistentSetOf("confirmed-archive"), current)
        assertEquals(listOf("confirmed-pin"), registry.pinned)
        assertEquals(setOf("confirmed-archive"), registry.archived)

        val sessions =
            (1..10000).map {
                decodeSessionSummary(parseObject("""{"sessionId":"s$it","cwd":"/same-path"}"""))
            }
        val active = workspaceSessions(sessions, workspace, setOf("s3"), false, listOf("s9000"))
        assertEquals(listOf("s9000", "s2"), active.map { it.sessionId })
        assertSame(sessions[8999], active.first())
        assertEquals(
            listOf("s3"),
            workspaceSessions(sessions, workspace, setOf("s3"), true, listOf("s9000")).map {
                it.sessionId
            },
        )
        assertEquals(9999, workspaceSessions(sessions, null, setOf("s3"), false, emptyList()).size)
    }
}

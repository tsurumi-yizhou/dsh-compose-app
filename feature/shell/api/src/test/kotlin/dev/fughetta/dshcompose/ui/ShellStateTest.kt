package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.protocol.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ShellStateTest {
    private fun row(id: String, at: Long = 10, running: Boolean = false, parent: String? = null) =
        SessionSummary(
            id,
            id,
            null,
            running,
            false,
            true,
            parent,
            if (parent == null) null else "subagent",
            at,
        )

    @Test
    fun foldersPinsFiltersAndCollapsedSelectionShowEachSessionOnce() {
        var view =
            DrawerView()
                .createFolder(DrawerFolder("a", " A ", sessionIds = persistentListOf("1", "2")))
                .createFolder(
                    DrawerFolder("b", "B", collapsed = true, sessionIds = persistentListOf("2")),
                )
        assertEquals(mapOf("1" to "a", "2" to "b"), view.folderOf)
        view = view.moveSessions(listOf("1", "1"), "b")
        assertEquals(listOf("1", "2"), view.folders.first().sessionIds)
        assertEquals(view, view.moveSessions(listOf("1"), "missing"))
        val sessions = listOf(row("1"), row("2"), row("3"), row("4", running = true))
        val blocks = drawerBlocks(sessions, listOf("1", "3"), view, now = 10)
        assertEquals(listOf("3", "1", "2", "4"), blocks.flatMap { it.items }.map { it.sessionId })
        assertEquals(listOf("3", "4"), drawerSelectableIds(blocks))
        assertEquals(
            listOf("4"),
            drawerBlocks(sessions, emptyList(), view, DrawerFilter.Running).single().items.map {
                it.sessionId
            },
        )
        assertEquals(
            listOf("2"),
            drawerBlocks(
                    sessions,
                    emptyList(),
                    view,
                    DrawerFilter.Pending,
                    mapOf("2" to PendingKind.Question),
                )
                .single()
                .items
                .map { it.sessionId },
        )
        assertEquals(
            listOf("1"),
            drawerBlocks(sessions, emptyList(), view, DrawerFilter.Unread, unread = setOf("1"))
                .single()
                .items
                .map { it.sessionId },
        )
        view = view.forgetMissing(setOf("2", "3", "4"))
        assertEquals(mapOf("2" to "b"), view.folderOf)
        assertTrue(view.deleteFolder("b").folderOf.isEmpty())
        assertEquals(listOf("a", "b"), view.reorderFolder(0, 1).folders.map { it.id })
    }

    @Test
    fun selectionStaysActiveWhenEmptyAndDoesNotToggleBeforeStart() {
        val off = DrawerSelection()
        assertEquals(off, off.toggle("x"))
        val empty = off.start("x").toggle("x")
        assertTrue(empty.active)
        assertTrue(empty.ids!!.isEmpty())
        assertEquals(setOf("a", "b"), empty.selectAll(listOf("a", "b", "a")).ids)
        assertFalse(empty.end().active)
    }

    @Test
    fun dayGroupingUsesLocalCalendarAndMondayAcrossDst() {
        fun ms(s: String) = Instant.parse(s).toEpochMilli()
        val zone = ZoneId.of("America/New_York")
        val now = ms("2026-03-09T04:30:00Z") // Monday, after the DST transition.
        assertEquals(SessionDay.Today, sessionDayOf(now, now, zone))
        assertEquals(SessionDay.Yesterday, sessionDayOf(ms("2026-03-08T05:30:00Z"), now, zone))
        assertEquals(SessionDay.Earlier, sessionDayOf(ms("2026-03-07T05:30:00Z"), now, zone))
        assertEquals(SessionDay.ThisWeek, sessionDayOf(now, ms("2026-03-12T04:30:00Z"), zone))
    }

    @Test
    fun firstInventoryIsSeenThenIdleActivityBecomesUnreadUntilViewed() {
        val initial = listOf(row("a", 100), row("b", 50))
        var seen = DrawerSeen().observe(initial, null)
        assertTrue(seen.unread(initial, null).isEmpty())
        val updated = listOf(row("a", 101), row("b", 120, running = true), row("c", 130))
        assertEquals(setOf("a", "c"), seen.unread(updated, null))
        seen = seen.observe(updated, "a")
        assertEquals(setOf("c"), seen.unread(updated, null))
        assertTrue(seen.unread(updated, "c").isEmpty())
        assertEquals(setOf("c"), seen.observe(listOf(row("a", 99)), "a").unread(updated, null))
    }

    @Test
    fun childPendingUsesRootAndApprovalTakesPrecedence() {
        val sessions =
            listOf(row("root"), row("child", parent = "root"), row("grandchild", parent = "child"))
        assertEquals(
            mapOf("root" to PendingKind.Approval),
            sessionPendingInteractions(
                listOf(
                    "root" to PendingKind.Question,
                    "child" to PendingKind.Plan,
                    "grandchild" to PendingKind.Approval,
                ),
                sessions,
            ),
        )
    }

    @Test
    fun creationSortUsesStoredFirstEventInsteadOfRecentActivity() {
        val view =
            DrawerView(
                sort = DrawerSort.Created,
                createdAt = persistentMapOf("a" to 1, "b" to 2),
            )
        val rows =
            drawerBlocks(listOf(row("a", 100), row("b", 50)), emptyList(), view, now = 100)
                .flatMap { it.items }
        assertEquals(listOf("b", "a"), rows.map { it.sessionId })
        assertEquals(mapOf("b" to 2L), view.forgetMissing(setOf("b")).createdAt)
    }
}

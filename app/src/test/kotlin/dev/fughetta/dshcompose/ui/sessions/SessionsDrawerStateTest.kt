package dev.fughetta.dshcompose.ui.sessions

import dev.fughetta.dshcompose.domain.messages.QueueChange
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsDrawerStateTest {
    private class Preferences : dev.fughetta.dshcompose.testing.FakeSettingsRepository() {
        val values = mutableMapOf<String, DrawerPreferences>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val writes = mutableListOf<String>()
        var readFailure = false
        var writeFailure = false

        override fun watchDrawer(host: String) = flow {
            gates[host]?.await()
            if (readFailure) throw DrawerPreferencesException("drawer/preferences-read-failed")
            emit(values[host] ?: DrawerPreferences())
        }

        override suspend fun updateDrawer(
            host: String,
            transform: (DrawerPreferences) -> DrawerPreferences,
        ) {
            if (writeFailure) throw DrawerPreferencesException("drawer/preferences-write-failed")
            values[host] = transform(values[host] ?: DrawerPreferences())
            writes += host
        }
    }

    private fun row(id: String, running: Boolean = false, at: Long = 10) =
        SessionSummary(id, id, null, running, false, true, null, null, at)

    private class Operations(val read: suspend (String) -> Long?) :
        dev.fughetta.dshcompose.testing.TestSessionsRepository() {
        override var active = true

        override suspend fun createdAt(sessionId: String) = read(sessionId)

        override suspend fun create(
            cwd: String?,
            workspaceId: String?,
            agentPreset: String?,
        ): String = error("unused")

        override suspend fun rename(sessionId: String, title: String): String = error("unused")

        override suspend fun forkSession(sessionId: String, atSeq: Long?): String = error("unused")

        override suspend fun forkState(
            sessionId: String,
        ): dev.fughetta.dshcompose.domain.sessions.SessionForkState? = error("unused")

        override suspend fun updateQueuedPrompt(
            sessionId: String,
            itemId: String,
            change: QueueChange,
        ): Unit = error("unused")

        override suspend fun delete(sessionId: String): Unit = error("unused")
    }

    @Test
    fun creationSortFollowsCatalogGrowthWhileHeaderReadsArePending() = runTest {
        val preferences = Preferences()
        preferences.values["host"] = DrawerPreferences(view = DrawerView(sort = DrawerSort.Created))
        val catalog =
            MutableStateFlow(
                SessionCatalogState(
                    available = true,
                    sessions = persistentListOf(row("a"), row("b")),
                ),
            )
        val answers = listOf("a", "b", "c").associateWith { CompletableDeferred<Long?>() }
        val reads = mutableListOf<String>()
        val operations = Operations { id ->
            reads += id
            answers.getValue(id).await()
        }
        val drawer = SessionsDrawerState(preferences, backgroundScope, catalog)
        drawer.load("host")
        drawer.connect("host", operations)
        runCurrent()
        assertEquals(setOf("a", "b"), reads.toSet())
        catalog.value =
            catalog.value.copy(sessions = persistentListOf(row("a"), row("b"), row("c")))
        runCurrent()
        answers.getValue("b").complete(30)
        runCurrent()
        assertTrue("c" in reads)
        answers.getValue("a").complete(10)
        answers.getValue("c").complete(20)
        runCurrent()
        val app = AppState(workspaces = WorkspacesUiState(preferencesLoaded = true))
        assertEquals(
            listOf("b", "c", "a"),
            app.drawerBlocks(catalog.value, drawer.state.value)!!
                .flatMap { it.items }
                .map { it.sessionId },
        )
        assertEquals(
            mapOf("a" to 10L, "b" to 30L, "c" to 20L),
            preferences.values.getValue("host").view.createdAt,
        )
    }

    @Test
    fun disablingSortAndChangingHostDiscardsTheRetiredRead() = runTest {
        val preferences = Preferences()
        preferences.values["b"] = DrawerPreferences(view = DrawerView(sort = DrawerSort.Created))
        val catalog =
            MutableStateFlow(
                SessionCatalogState(available = true, sessions = persistentListOf(row("same"))),
            )
        val oldHeader = CompletableDeferred<Long?>()
        var cancelled = false
        var oldReads = 0
        val old = Operations {
            oldReads++
            try {
                oldHeader.await()
            } finally {
                cancelled = true
            }
        }
        val drawer = SessionsDrawerState(preferences, backgroundScope, catalog)
        drawer.load("a")
        drawer.connect("a", old)
        runCurrent()
        drawer.handle(DrawerAction.Sort(DrawerSort.Created))
        runCurrent()
        drawer.handle(DrawerAction.Sort(DrawerSort.Activity))
        runCurrent()
        assertTrue(cancelled)
        drawer.load("b")
        runCurrent()
        assertEquals(1, oldReads)
        assertTrue(drawer.state.value.view.createdAt.isEmpty())
        drawer.connect("b", Operations { 22L })
        runCurrent()
        oldHeader.complete(11)
        runCurrent()
        assertEquals(mapOf("same" to 22L), drawer.state.value.view.createdAt)
        assertTrue(preferences.values.getValue("a").view.createdAt.isEmpty())
        assertEquals(mapOf("same" to 22L), preferences.values.getValue("b").view.createdAt)
    }

    @Test
    fun drawerProjectionSharesSelectionAndDeletionCleanupThenResetsForAnotherHost() = runTest {
        val preferences = Preferences()
        val drawer =
            SessionsDrawerState(
                preferences,
                backgroundScope,
                MutableStateFlow(SessionCatalogState()),
            )
        val app = AppState(workspaces = WorkspacesUiState(preferencesLoaded = true))
        var catalog =
            SessionCatalogState(sessions = persistentListOf(row("a", running = true), row("b")))
        preferences.values["first"] =
            DrawerPreferences(
                view =
                    DrawerView()
                        .createFolder(
                            DrawerFolder(
                                "folder",
                                "Projects",
                                sessionIds = persistentListOf("a", "b"),
                            ),
                        ),
            )
        drawer.load("first")
        runCurrent()
        drawer.handle(DrawerAction.Filter(DrawerFilter.Running))
        val visible = drawerSelectableIds(app.drawerBlocks(catalog, drawer.state.value)!!)
        drawer.handle(DrawerAction.StartSelection())
        drawer.handle(DrawerAction.SelectAll(visible))
        assertEquals(setOf("a"), drawer.state.value.selection.ids)
        drawer.handle(DrawerAction.Filter(DrawerFilter.All))
        drawer.handle(DrawerAction.SelectAll(listOf("a", "b")))
        // Only an acknowledged deletion leaves the catalog and the retained selection.
        catalog = catalog.copy(sessions = persistentListOf(row("b")))
        drawer.removeDeleted(setOf("a"))
        runCurrent()
        assertEquals(
            listOf("b"),
            drawerSelectableIds(app.drawerBlocks(catalog, drawer.state.value)!!),
        )
        assertEquals(setOf("b"), drawer.state.value.selection.ids)
        assertEquals(mapOf("b" to "folder"), drawer.state.value.view.folderOf)
        drawer.load("second")
        assertNull(app.drawerBlocks(catalog, drawer.state.value))
        runCurrent()
        assertFalse(drawer.state.value.selection.active)
        assertTrue(
            app.drawerBlocks(catalog, drawer.state.value)!!.none { it is DrawerBlock.Folder },
        )
    }

    @Test
    fun delayedLoadMergesSeenProgressAndAppliesQueuedEditsAndAcknowledgedDeletion() = runTest {
        val preferences = Preferences()
        val ready = CompletableDeferred<Unit>()
        preferences.gates["host"] = ready
        preferences.values["host"] =
            DrawerPreferences(
                DrawerView()
                    .createFolder(
                        DrawerFolder(
                            "folder",
                            "Stored",
                            sessionIds = persistentListOf("id", "retired"),
                        ),
                    ),
                DrawerSeen(100, persistentMapOf("id" to 120, "retired" to 30)),
            )
        val drawer =
            SessionsDrawerState(
                preferences,
                backgroundScope,
                MutableStateFlow(SessionCatalogState()),
            )
        drawer.load("host")
        drawer.markSeen("id", 150)
        drawer.handle(DrawerAction.CreateFolder("Queued"))
        drawer.removeDeleted(setOf("retired"))
        runCurrent()
        assertFalse(drawer.state.value.loaded)
        assertTrue(preferences.writes.isEmpty())
        ready.complete(Unit)
        runCurrent()
        assertEquals(
            setOf("Queued", "Stored"),
            drawer.state.value.view.folders.map { it.name }.toSet(),
        )
        assertEquals(150L, drawer.state.value.seen.seen["id"])
        drawer.observeSessions(listOf(row("id", at = 200)), "id")
        runCurrent()
        val saved = preferences.values.getValue("host")
        assertEquals(mapOf("id" to "folder"), saved.view.folderOf)
        assertEquals(100L, saved.seen.initialActivity)
        assertEquals(mapOf("id" to 200L), saved.seen.seen)
    }

    @Test
    fun switchingAwayAndBackDiscardsEditsQueuedForTheRetiredLoad() = runTest {
        val preferences = Preferences()
        val ready = CompletableDeferred<Unit>()
        preferences.gates["a"] = ready
        preferences.values["b"] = DrawerPreferences(view = DrawerView(sort = DrawerSort.Created))
        val drawer =
            SessionsDrawerState(
                preferences,
                backgroundScope,
                MutableStateFlow(SessionCatalogState()),
            )
        drawer.load("a")
        drawer.markSeen("old", 500)
        drawer.mutate { it.createFolder(DrawerFolder("retired", "Retired")) }
        runCurrent()
        drawer.load("b")
        runCurrent()
        assertEquals(DrawerSort.Created, drawer.state.value.view.sort)
        assertTrue(drawer.state.value.seen.seen.isEmpty())
        drawer.load("a")
        ready.complete(Unit)
        runCurrent()
        assertTrue(drawer.state.value.loaded)
        assertTrue(drawer.state.value.view.folders.isEmpty())
        assertTrue(drawer.state.value.seen.seen.isEmpty())
        assertTrue(preferences.writes.isEmpty())
    }

    @Test
    fun storageFailuresKeepThePageUsableAndReloadRecovers() = runTest {
        val preferences = Preferences().apply { readFailure = true }
        val drawer =
            SessionsDrawerState(
                preferences,
                backgroundScope,
                MutableStateFlow(SessionCatalogState()),
            )
        drawer.load("host")
        runCurrent()
        assertTrue(drawer.state.value.loaded)
        assertEquals("drawer/preferences-read-failed", drawer.state.value.failure)
        preferences.writeFailure = true
        drawer.handle(DrawerAction.CreateFolder("Visible"))
        runCurrent()
        assertEquals("Visible", drawer.state.value.view.folders.single().name)
        assertEquals("drawer/preferences-write-failed", drawer.state.value.failure)
        assertEquals(2L, drawer.state.value.failureRevision)
        preferences.readFailure = false
        preferences.writeFailure = false
        drawer.load("host")
        runCurrent()
        assertNull(drawer.state.value.failure)
        drawer.handle(DrawerAction.CreateFolder("Saved"))
        runCurrent()
        assertEquals("Saved", preferences.values.getValue("host").view.folders.single().name)
    }
}

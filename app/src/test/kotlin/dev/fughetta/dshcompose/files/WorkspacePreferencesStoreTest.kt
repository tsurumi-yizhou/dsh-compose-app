package dev.fughetta.dshcompose.files

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.settings.WorkspacePreferencesStore
import dev.fughetta.dshcompose.ui.workspaceSections
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePreferencesStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun transactionsKeepHostPreferencesSeparateAndRestoreGroupsAndSelectionAfterReopening() =
        runBlocking {
            val file = File(temporary.root, "workspaces.preferences_pb")
            var storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            fun store() =
                WorkspacePreferencesStore(
                    PreferenceDataStoreFactory.create(scope = storageScope, produceFile = { file }),
                )
            var preferences = store()
            try {
                coroutineScope {
                    launch { preferences.update("local") { it.copy(selected = "w2") } }
                    launch { preferences.update("local") { it.grouped("w1", " Projects ") } }
                    launch {
                        preferences.update("local") { it.copy(pinned = it.pinned.adding("w2")) }
                    }
                    launch {
                        preferences.update("remote-id") {
                            it.copy(selected = "remote-workspace").grouped("r1", "Remote")
                        }
                    }
                }
                for (path in listOf("/a", "/b", "/c", "/d", "/e", "/c")) preferences.update(
                    "local",
                ) {
                    it.remembered(path)
                }
                storageScope.coroutineContext.job.cancelAndJoin()
                storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                preferences = store()
                val local = preferences.watch("local").first()
                assertEquals("w2", local.selected)
                assertEquals(setOf("w2"), local.pinned)
                assertEquals(mapOf("w1" to "Projects"), local.groups)
                assertEquals(listOf("/c", "/e", "/d", "/b"), local.recent)
                assertEquals("remote-workspace", preferences.watch("remote-id").first().selected)
                fun row(id: String) = Workspace(id, "/$id", id, persistentSetOf(), null)
                val sections = workspaceSections(listOf(row("w1"), row("w2"), row("w3")), local)
                assertEquals(listOf("pinned", "group", "rest"), sections.map { it.kind })
                assertEquals(listOf("w2", "w1", "w3"), sections.flatMap { it.rows }.map { it.id })
                assertEquals("Projects", sections[1].name)
                preferences.update("local") { it.forgotten("w2").grouped("w1", "") }
                val forgotten = preferences.watch("local").first()
                assertNull(forgotten.selected)
                assertTrue(forgotten.pinned.isEmpty())
                assertTrue(forgotten.groups.isEmpty())
                assertEquals("remote-workspace", preferences.watch("remote-id").first().selected)
            } finally {
                storageScope.coroutineContext.job.cancelAndJoin()
            }
        }
}

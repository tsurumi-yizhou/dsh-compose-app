package dev.fughetta.dshcompose.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.fughetta.dshcompose.domain.sessions.*
import java.io.File
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDrawerPreferencesRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun submittedWriteCompletesAfterTheCallingPageIsCancelled() = runBlocking {
        withTimeout(10_000) {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stored = MutableStateFlow<Preferences>(emptyPreferences())
            val dataStore =
                object : DataStore<Preferences> {
                    override val data = stored

                    override suspend fun updateData(
                        transform: suspend (Preferences) -> Preferences,
                    ): Preferences {
                        entered.complete(Unit)
                        release.await()
                        return transform(stored.value).also { stored.value = it }
                    }
                }
            val repository =
                AppSettingsRepository(
                    drawerSource = DrawerPreferencesDataSource(dataStore),
                    applicationScope = applicationScope,
                )
            try {
                val caller = launch {
                    repository.updateDrawer("local") {
                        it.copy(view = it.view.copy(sort = DrawerSort.Created))
                    }
                }
                entered.await()
                caller.cancelAndJoin()
                release.complete(Unit)
                val saved =
                    repository.watchDrawer("local").first { it.view.sort == DrawerSort.Created }
                assertEquals(DrawerSort.Created, saved.view.sort)
                assertEquals(
                    DrawerSort.Activity,
                    repository.watchDrawer("another-host").first().view.sort,
                )
            } finally {
                applicationScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun folderAndSeenTransactionsSurviveReopeningAndKeepHostsSeparate() = runBlocking {
        val file = File(temporary.root, "drawer.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun open() =
            AppSettingsRepository(
                drawerSource =
                    DrawerPreferencesDataSource(
                        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
                    ),
                applicationScope = scope,
            )
        var store = open()
        try {
            coroutineScope {
                launch {
                    store.updateDrawer("local") {
                        it.copy(
                            view =
                                it.view.createFolder(
                                    DrawerFolder(
                                        "f",
                                        "Projects",
                                        2,
                                        "⭐",
                                        true,
                                        persistentListOf("archived", "s"),
                                    ),
                                ),
                        )
                    }
                }
                launch {
                    store.updateDrawer("local") {
                        it.copy(seen = DrawerSeen(100, persistentMapOf("s" to 120)))
                    }
                }
                launch {
                    store.updateDrawer("remote-id") {
                        it.copy(
                            view =
                                it.view.copy(
                                    sort = DrawerSort.Created,
                                    createdAt = persistentMapOf("remote-session" to 123L),
                                ),
                        )
                    }
                }
            }
            scope.coroutineContext.job.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = open()
            val restored = store.watchDrawer("local").first()
            assertEquals(listOf("archived", "s"), restored.view.folders.single().sessionIds)
            assertTrue(restored.view.folders.single().collapsed)
            assertEquals("⭐", restored.view.folders.single().emoji)
            assertEquals(120L, restored.seen.seen["s"])
            assertEquals(100L, restored.seen.initialActivity)
            assertEquals(DrawerSort.Activity, restored.view.sort)
            assertEquals(DrawerSort.Created, store.watchDrawer("remote-id").first().view.sort)
            assertTrue(store.watchDrawer("remote-id").first().view.folders.isEmpty())
            assertEquals(
                mapOf("remote-session" to 123L),
                store.watchDrawer("remote-id").first().view.createdAt,
            )
            store.updateDrawer("local") { it.copy(view = it.view.forgetMissing(setOf("archived"))) }
            assertEquals(
                listOf("archived"),
                store.watchDrawer("local").first().view.folders.single().sessionIds,
            )
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}

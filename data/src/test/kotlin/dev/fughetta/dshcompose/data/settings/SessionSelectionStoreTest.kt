package dev.fughetta.dshcompose.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.fughetta.dshcompose.domain.settings.PreferenceGroup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class SessionSelectionStoreTest {
    @Test
    fun restartRestoresTheSameHostsConversationAndRespectsDraftAndDeletion() = runBlocking {
        val persisted = MutableStateFlow(emptyPreferences())
        val disk =
            object : DataStore<Preferences> {
                override val data = persisted.asStateFlow()

                override suspend fun updateData(
                    transform: suspend (Preferences) -> Preferences,
                ): Preferences = transform(persisted.value).also { persisted.value = it }
            }
        var job = SupervisorJob()
        suspend fun open(): Pair<SessionSelectionStore, PreferenceGroup> {
            val scope = CoroutineScope(job + Dispatchers.IO)
            val storage = PreferenceStorage(disk, scope, disk.data.first()) { fail("write failed") }
            val preferences = storage.group("host_selection")
            return SessionSelectionStore(preferences) to preferences
        }
        try {
            var (selection, preferences) = open()
            selection.select("ssh-a", "chat-a")
            selection.select("ssh-b", "chat-b")
            assertTrue(
                withContext(Dispatchers.IO) {
                    preferences.edit().putString("selected", "ssh-a").commit()
                },
            )
            job.cancelAndJoin()
            job = SupervisorJob()
            val reopened = open()
            selection = reopened.first
            preferences = reopened.second
            assertEquals("ssh-a", preferences.getString("selected", null))
            assertEquals("chat-a", selection.restore("ssh-a", setOf("chat-a", "other")))
            assertEquals("chat-b", selection.restore("ssh-b", setOf("chat-b")))
            assertNull(selection.restore("local", setOf("chat-a")))

            selection.forget("ssh-a", "other")
            assertEquals("chat-a", selection.restore("ssh-a", setOf("chat-a")))
            selection.forget("ssh-a", "chat-a")
            selection.select("ssh-b", null)
            selection.select("ssh-removed", "gone")
            assertNull(selection.restore("ssh-removed", emptySet()))
            assertTrue(
                withContext(Dispatchers.IO) {
                    preferences.edit().putString("selected", "ssh-b").commit()
                },
            )
            job.cancelAndJoin()
            job = SupervisorJob()
            selection = open().first
            assertNull(selection.restore("ssh-a", setOf("chat-a")))
            assertNull(selection.restore("ssh-b", setOf("chat-b")))
            assertNull(selection.restore("ssh-removed", setOf("gone")))
        } finally {
            job.cancelAndJoin()
        }
    }
}

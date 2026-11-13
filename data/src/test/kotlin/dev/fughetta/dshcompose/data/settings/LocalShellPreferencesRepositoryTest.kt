package dev.fughetta.dshcompose.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.fughetta.dshcompose.domain.settings.ShellPreferences
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LocalShellPreferencesRepositoryTest {
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        override val data = values.asStateFlow()
        var fail = false

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            if (fail) {
                fail = false
                throw IOException("write failed")
            }
            return transform(values.value).also { values.value = it }
        }
    }

    @Test
    fun concurrentSearchAndRiskChangesRemainOrderedAndRestoreFromTheSameNamespace() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val disk = Disk()
        try {
            val storage =
                PreferenceStorage(disk, scope, emptyPreferences()) { fail("write failed") }
            val source = ShellPreferencesDataSource(storage.group("dsh_native_shell"))
            val repository = AppSettingsRepository(shellSource = source)
            repository.rememberSearch("  first\nquery  ")
            coroutineScope {
                launch { repository.rememberSearch("second") }
                launch { repository.rememberSearch("third") }
                launch { repository.silenceRisk("risk-a") }
                launch { repository.silenceRisk("risk-b") }
            }
            assertEquals(
                setOf("first query", "second", "third"),
                repository.shell.first().recentSearches.toSet(),
            )
            assertEquals(setOf("risk-a", "risk-b"), repository.shell.first().silencedRisks)
            for (i in 0..6) repository.rememberSearch("query $i")
            repository.quickPanelItems(listOf("settings", "plugins", "settings"))
            val reopened =
                AppSettingsRepository(
                    shellSource = ShellPreferencesDataSource(storage.group("dsh_native_shell")),
                )
            val restored = reopened.shell.first()
            assertEquals((6 downTo 2).map { "query $it" }, restored.recentSearches)
            assertEquals(ShellPreferences.RecentLimit, restored.recentSearches.size)
            assertEquals(listOf("settings", "plugins"), restored.quickPanelItems)
            assertEquals(restored, repository.shell.first())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun failedDurableWriteRollsBackThePublishedValueAndLaterWritesStillSucceed() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val disk = Disk()
        var failures = 0
        try {
            val storage = PreferenceStorage(disk, scope, emptyPreferences()) { failures++ }
            val repository =
                AppSettingsRepository(
                    shellSource = ShellPreferencesDataSource(storage.group("dsh_native_shell")),
                )
            disk.fail = true
            try {
                repository.quickPanelSwipe(false)
                fail("Write failure must reach the ViewModel")
            } catch (error: IOException) {
                assertEquals("settings/write-failed", error.message)
            }
            assertTrue(repository.shell.first().quickPanelSwipe)
            assertEquals(1, failures)
            repository.backgroundGuideSeen()
            assertTrue(repository.shell.first().backgroundGuideSeen)
        } finally {
            job.cancelAndJoin()
        }
    }
}

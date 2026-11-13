package dev.fughetta.dshcompose.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class LocalPreferencesTest {
    @Test
    fun writesPersistInOrderAndClearOnlyTheirNamespace() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val file =
            kotlin.io.path
                .createTempDirectory("local-preferences")
                .resolve("settings.preferences_pb")
                .toFile()
        val disk = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val storage = PreferenceStorage(disk, scope, disk.data.first()) { fail("write failed") }
            val phone = storage.group("phone")
            val overlay = storage.group("phone:overlay")
            phone.edit().putBoolean("enabled", true).apply()
            overlay.edit().putFloat("opacity", .7f).apply()
            assertTrue(phone.getBoolean("enabled", false))
            assertEquals(.7f, overlay.getFloat("opacity", 0f))
            assertTrue(withContext(Dispatchers.IO) { phone.edit().clear().commit() })
            assertFalse(phone.contains("enabled"))
            assertEquals(mapOf("opacity" to .7f), overlay.data.first())
            val persisted = disk.data.first().asMap().values
            assertEquals(listOf(.7f), persisted.toList())
        } finally {
            job.cancelAndJoin()
            file.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun failedWriteRollsBackWithoutLosingLaterEdits() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = MutableStateFlow(emptyPreferences())
        var writes = 0
        var failures = 0
        val disk =
            object : DataStore<Preferences> {
                override val data = persisted.asStateFlow()

                override suspend fun updateData(
                    transform: suspend (Preferences) -> Preferences,
                ): Preferences {
                    if (++writes == 1) {
                        started.complete(Unit)
                        release.await()
                        throw IOException("disk unavailable")
                    }
                    return transform(persisted.value).also { persisted.value = it }
                }
            }
        try {
            val storage = PreferenceStorage(disk, scope, emptyPreferences()) { failures++ }
            val first = storage.submit { it[stringPreferencesKey("first")] = "lost" }
            started.await()
            val next = storage.submit { it[stringPreferencesKey("next")] = "saved" }
            release.complete(Unit)
            assertFalse(withTimeout(5000) { first.await() })
            assertTrue(withTimeout(5000) { next.await() })
            assertEquals(1, failures)
            assertNull(storage.data.value[stringPreferencesKey("first")])
            assertEquals("saved", disk.data.first()[stringPreferencesKey("next")])
        } finally {
            job.cancelAndJoin()
        }
    }
}

package dev.fughetta.dshcompose.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.fughetta.dshcompose.domain.settings.Settings
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import dev.fughetta.dshcompose.domain.settings.ThemeMode
import dev.fughetta.dshcompose.protocol.HostConnection
import dev.fughetta.dshcompose.protocol.ProtocolClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun defaultsAndUnrecognizedValuesProduceDomainSettings() = runBlocking {
        val job = SupervisorJob()
        val store =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
                File(temporary.root, "defaults.preferences_pb")
            }
        try {
            val repository = AppSettingsRepository(settingsSource = SettingsDataSource(store))
            val defaults = Settings(ThemeMode.System, true, false, true)
            assertEquals(defaults, repository.data.first())
            store.edit { it[SettingsKeys.theme] = "unrecognized" }
            assertEquals(defaults, repository.data.first())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun concurrentChangesRemainIndependentAndSurviveReopening() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withRepository(file) { repository ->
            val changes =
                listOf(
                    launch { repository.theme(ThemeMode.Dark) },
                    launch { repository.dynamicColor(false) },
                    launch { repository.confirmAutoReview() },
                    launch { repository.foldCompletedTurns(false) },
                )
            changes.forEach { it.join() }
        }
        withRepository(file) { repository ->
            assertEquals(
                Settings(ThemeMode.Dark, false, true, false),
                repository.data.first(),
            )
        }
    }

    @Test
    fun replacingHostRetiresDocumentCallsAndDiscardsLateResponses() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var firstReads = 0
            var nextReads = 0
            val document =
                """{"entryId":"example","revision":"current","schema":{"uid":0,"refs":{"0":{"type":"object","dict":{}}}},"value":{},"secrets":[],"writable":true}"""
            val first =
                ProtocolClient(
                    HostConnection(
                        "https://first.test",
                        engine =
                            MockEngine {
                                firstReads++
                                entered.complete(Unit)
                                release.await()
                                respond(
                                    document,
                                    headers =
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                    ),
                )
            val next =
                ProtocolClient(
                    HostConnection(
                        "https://next.test",
                        engine =
                            MockEngine {
                                nextReads++
                                respond(
                                    document,
                                    headers =
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            },
                    ),
                )
            val repository = AppSettingsRepository()
            try {
                repository.connect(first)
                val old = repository.connection.value!!
                repository.connect(first)
                assertSame(old, repository.connection.value)
                val pending = async { old.readPluginConfig("example") }
                entered.await()
                repository.connect(next)
                val current = repository.connection.value!!
                assertNotSame(old, current)
                release.complete(Unit)
                try {
                    pending.await()
                    fail("The retired host's response must be discarded")
                } catch (_: CancellationException) {}
                try {
                    old.readPluginConfig("example")
                    fail("A retired repository must not serve a new request")
                } catch (_: CancellationException) {}
                assertEquals(1, firstReads)
                assertEquals(0, nextReads)
                assertEquals("current", current.readPluginConfig("example").revision)
                assertEquals(1, nextReads)
                repository.disconnect()
                assertNull(repository.connection.value)
                assertNull(current.connection.value)
            } finally {
                release.complete(Unit)
                repository.disconnect()
                first.close()
                next.close()
            }
        }
    }

    private suspend fun withRepository(
        file: File,
        action: suspend (SettingsRepository) -> Unit,
    ) {
        val job = SupervisorJob()
        val store =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            action(AppSettingsRepository(settingsSource = SettingsDataSource(store)))
        } finally {
            job.cancelAndJoin()
        }
    }
}

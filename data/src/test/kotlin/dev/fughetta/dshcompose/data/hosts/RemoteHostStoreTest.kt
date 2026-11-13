package dev.fughetta.dshcompose.data.hosts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteHostStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun credentialsAreEncryptedAndBoundToTheSavedServiceWhileFailedSavesLeaveTheOldGrantIntact() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val data =
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { File(temporary.root, "services.preferences_pb") },
                )
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            var available = true
            val cipher = DeviceCredentialCipher {
                if (!available) throw java.security.KeyStoreException("Key unavailable")
                key
            }
            val store = RemoteHostStore(data, cipher, retireCredentials = {})
            val host = RemoteHost("compose-one", "Home", "https://home.example")
            try {
                store.save(listOf(host), host.id, "private-device-grant")
                assertEquals("private-device-grant", store.credential(host.id))
                assertFalse(
                    data.data.first().asMap().values.toString().contains("private-device-grant"),
                )
                available = false
                try {
                    store.save(
                        listOf(host.copy(name = "Failed replacement")),
                        host.id,
                        "replacement",
                    )
                    fail("An unavailable key must abort the save")
                } catch (_: java.security.KeyStoreException) {}
                available = true
                assertEquals(listOf(host), store.load())
                assertEquals("private-device-grant", store.credential(host.id))
                val encrypted = cipher.encrypt(host, "private-device-grant")
                try {
                    cipher.decrypt(host.copy(url = "https://different.example"), encrypted)
                    fail("A grant must remain bound to its service")
                } catch (_: javax.crypto.AEADBadTagException) {}
                store.save(listOf(host.copy(name = "Renamed")))
                assertEquals("private-device-grant", store.credential(host.id))
                store.save(listOf(host.copy(url = "https://different.example")))
                assertNull(store.credential(host.id))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
}

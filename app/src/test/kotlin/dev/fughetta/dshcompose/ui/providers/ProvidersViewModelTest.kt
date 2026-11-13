package dev.fughetta.dshcompose.ui.providers

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProvidersViewModelTest {
    private val account = ProviderAccount("provider", "Provider", "KEY")
    private val loaded =
        ProviderSnapshot(
            null,
            persistentListOf(account),
            persistentMapOf("KEY" to CredentialStatus(true, true, "user")),
        )

    private inner class Operations : ProvidersRepository {
        override val changes = MutableStateFlow(0L)
        override val snapshot = MutableStateFlow<ProviderSnapshot?>(loaded)
        var read: suspend () -> ProviderSnapshot = { loaded }
        var save: suspend () -> Unit = {}

        override suspend fun refresh() = read()

        override suspend fun setCredential(ref: String, key: ProviderKey?) = save()

        override suspend fun apply(change: ProviderMutation): ProviderSettings = error("Unused")

        override suspend fun catalog(): ModelCatalog = error("Unused")

        override suspend fun discover(route: String): PersistentList<DiscoveredModel> =
            error("Unused")

        override suspend fun discover(
            baseUrl: String,
            api: ProviderApi,
            key: ProviderKey?,
        ): PersistentList<DiscoveredModel> = error("Unused")

        override fun invalidate() {
            changes.value++
        }
    }

    private class Repository(initial: ProvidersRepository) {
        val connection = MutableStateFlow<ProvidersRepository?>(initial)
    }

    @Test
    fun endpointConfirmationImportsModelsAndSavesKeyOnlyAfterDiscoverySucceeds() =
        endpointConfirmation(false)

    @Test
    fun magpieAcceptsBareAddressAndUsesDefaultKeyForDiscoveryAndSaving() =
        endpointConfirmation(true)

    @Test
    fun customProviderFetchesModelsBeforeSavingAndCanRetryDiscovery() =
        endpointConfirmation(false, custom = true)

    private fun endpointConfirmation(magpie: Boolean, custom: Boolean = false) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var settings =
                ProviderSettings(persistentListOf(), 1, true, persistentSetOf(), persistentSetOf())
            var failDiscovery = true
            var writes = 0
            var savedKey = false
            val operations =
                object : ProvidersRepository {
                    override val changes = MutableStateFlow(0L)
                    override val snapshot = MutableStateFlow<ProviderSnapshot?>(null)

                    override suspend fun refresh() =
                        ProviderSnapshot(settings, settings.accounts(), persistentMapOf())

                    override suspend fun setCredential(ref: String, key: ProviderKey?) {
                        assertEquals(if (magpie) "MAGPIE_API_KEY" else "DEEPSEEK_API_KEY", ref)
                        assertEquals(
                            if (magpie) "magpie" else "test-key",
                            key?.useCharacters { String(it) },
                        )
                        assertNotNull(key)
                        savedKey = true
                    }

                    override suspend fun apply(change: ProviderMutation): ProviderSettings {
                        val create = change as ProviderMutation.Create
                        writes++
                        assertEquals(if (magpie) "magpie" else "deepseek-api", create.route)
                        assertEquals(if (magpie) "Magpie" else "DeepSeek", create.name)
                        assertEquals(
                            if (magpie) "http://10.115.10.117:3425"
                            else "https://api.deepseek.com/v1",
                            create.baseUrl,
                        )
                        assertEquals(
                            listOf("deepseek-chat", "deepseek-reasoner"),
                            create.models.map { it.id },
                        )
                        settings =
                            ProviderSettings(
                                persistentListOf(
                                    ProviderProfile(
                                        create.route,
                                        create.name,
                                        create.api,
                                        create.baseUrl,
                                        create.credentialRef,
                                        create.models.toPersistentList(),
                                    ),
                                ),
                                2,
                                true,
                                persistentSetOf(create.route),
                                persistentSetOf(),
                            )
                        return settings
                    }

                    override suspend fun catalog(): ModelCatalog = error("Unused")

                    override suspend fun discover(route: String): PersistentList<DiscoveredModel> =
                        error("Unused")

                    override suspend fun discover(
                        baseUrl: String,
                        api: ProviderApi,
                        key: ProviderKey?,
                    ): PersistentList<DiscoveredModel> {
                        assertEquals(
                            if (magpie) "http://10.115.10.117:3425"
                            else "https://api.deepseek.com/v1",
                            baseUrl,
                        )
                        assertEquals(
                            if (magpie) "magpie" else "test-key",
                            key?.useCharacters { String(it) },
                        )
                        if (failDiscovery) error("Endpoint unavailable")
                        return persistentListOf(
                            DiscoveredModel("deepseek-chat", null, null, null, null),
                            DiscoveredModel("deepseek-reasoner", null, null, null, null),
                        )
                    }

                    override fun invalidate() {}
                }
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory {
                        initializer { ProvidersViewModel(MutableStateFlow(operations)) }
                    },
                )[ProvidersViewModel::class.java]
            model.handle(AppAction.ObserveProviders(true))
            runCurrent()
            model.handle(
                when {
                    custom -> AppAction.OpenProviderEditor()
                    magpie -> AppAction.OpenMagpieProvider
                    else -> AppAction.OpenDeepSeekProvider
                },
            )
            if (custom) {
                model.handle(AppAction.EditProviderField(ProviderEditorField.Route, "deepseek-api"))
                model.handle(AppAction.EditProviderField(ProviderEditorField.Name, "DeepSeek"))
                model.handle(
                    AppAction.EditProviderField(
                        ProviderEditorField.CredentialRef,
                        "DEEPSEEK_API_KEY",
                    ),
                )
                model.handle(
                    AppAction.EditProviderField(
                        ProviderEditorField.BaseUrl,
                        "https://api.deepseek.com/v1",
                    ),
                )
                assertTrue(model.uiState.value.editor!!.models.isEmpty())
            }
            if (magpie)
                model.handle(
                    AppAction.EditProviderField(ProviderEditorField.BaseUrl, "10.115.10.117:3425"),
                )
            assertTrue(validProviderBaseUrl(model.uiState.value.editor!!.endpointUrl))
            val failedKey = if (magpie) null else ProviderKey.from("test-key")
            val failedBuffer = failedKey?.useCharacters { it }
            model.handle(AppAction.SaveProviderEndpoint(failedKey))
            runCurrent()
            assertEquals(0, writes)
            assertFalse(savedKey)
            assertTrue(failedBuffer?.all { it == '\u0000' } != false)
            assertNotNull(model.uiState.value.editor?.error)
            failDiscovery = false
            val key = if (magpie) null else ProviderKey.from("test-key")
            val buffer = key?.useCharacters { it }
            model.handle(AppAction.SaveProviderEndpoint(key))
            runCurrent()
            assertEquals(1, writes)
            assertTrue(savedKey)
            assertTrue(buffer?.all { it == '\u0000' } != false)
            assertEquals(true, model.uiState.value.editor?.endpointSaved)
            assertEquals(
                listOf("deepseek-chat", "deepseek-reasoner"),
                model.uiState.value.editor?.models,
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun confirmedCredentialWriteClosesTheSecretEvenWhenTheStatusReadFails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val operations = Operations()
            val repository = Repository(operations)
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { ProvidersViewModel(repository.connection) } },
                )[ProvidersViewModel::class.java]
            model.handle(AppAction.ObserveProviders(true))
            runCurrent()
            model.handle(AppAction.ManageProviderKey(account, false))
            val input = ProviderKey.from("synthetic-input")
            val buffer = input.useCharacters { it }
            val acknowledged = CompletableDeferred<Unit>()
            operations.save = {
                acknowledged.await()
                operations.read = { error("Read failed after acknowledgment") }
            }
            model.handle(AppAction.SubmitProviderKey(input))
            runCurrent()
            model.handle(AppAction.ObserveProviders(false))
            acknowledged.complete(Unit)
            runCurrent()
            assertTrue(buffer.all { it == '\u0000' })
            assertNull(model.uiState.value.dialog)
            assertEquals("providers/credential_status_unconfirmed", model.uiState.value.error)
            assertFalse(model.uiState.value.writing)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun replacingTheHostDiscardsALateReadAndTheOldCredentialEditor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val old = Operations()
            val repository = Repository(old)
            val model =
                ViewModelProvider(
                    store,
                    viewModelFactory { initializer { ProvidersViewModel(repository.connection) } },
                )[ProvidersViewModel::class.java]
            model.handle(AppAction.ObserveProviders(true))
            runCurrent()
            model.handle(AppAction.ManageProviderKey(account, false))
            val late = CompletableDeferred<ProviderSnapshot>()
            old.read = { withContext(NonCancellable) { late.await() } }
            model.handle(AppAction.RefreshProviders)
            runCurrent()
            val next = Operations().apply { read = { loaded.copy(accounts = persistentListOf()) } }
            repository.connection.value = next
            runCurrent()
            late.complete(loaded)
            runCurrent()
            assertTrue(model.uiState.value.accounts.isEmpty())
            assertNull(model.uiState.value.dialog)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

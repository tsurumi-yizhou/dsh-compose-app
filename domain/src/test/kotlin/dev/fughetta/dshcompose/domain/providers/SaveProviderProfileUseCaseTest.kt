package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SaveProviderProfileUseCaseTest {
    private class Operations(private val settings: ProviderSettings) : ProvidersRepository {
        override val changes = MutableStateFlow(0L)
        override val snapshot = MutableStateFlow<ProviderSnapshot?>(null)
        var mutation: ProviderMutation? = null

        override suspend fun apply(change: ProviderMutation): ProviderSettings {
            mutation = change
            return settings
        }

        override suspend fun refresh(): ProviderSnapshot = error("Unused")

        override suspend fun setCredential(ref: String, key: ProviderKey?) = error("Unused")

        override suspend fun catalog(): ModelCatalog = error("Unused")

        override suspend fun discover(route: String): PersistentList<DiscoveredModel> =
            error("Unused")

        override suspend fun discover(
            baseUrl: String,
            api: ProviderApi,
            key: ProviderKey?,
        ): PersistentList<DiscoveredModel> = error("Unused")

        override fun invalidate() {}
    }

    @Test
    fun savingAProfileUsesItsOriginalRevisionAndOnlyChangesEditedFields() = runBlocking {
        val model = ProviderModelSpec.create("model")
        val profile =
            ProviderProfile(
                "gateway",
                "Gateway",
                ProviderApi.AnthropicMessages,
                "https://example.test",
                "KEY",
                persistentListOf(model),
            )
        val settings =
            ProviderSettings(
                persistentListOf(profile),
                40,
                true,
                persistentSetOf("gateway"),
                persistentSetOf(),
            )
        val operations = Operations(settings)
        val draft =
            ProviderProfileDraft(
                17,
                profile,
                "gateway",
                " Renamed ",
                ProviderApi.AnthropicMessages,
                "https://example.test/v1/",
                " KEY ",
                listOf(model),
                emptyMap(),
                false,
            )
        assertSame(settings, SaveProviderProfileUseCase()(operations, settings, draft))
        val change = operations.mutation as ProviderMutation.Update
        assertEquals(17L, change.revision)
        assertEquals("Renamed", change.name)
        assertEquals("https://example.test", change.baseUrl)
        assertEquals("KEY", change.credentialRef)
        assertNull(change.api)
        assertNull(change.models)
        assertNull(change.overrides)
    }

    @Test
    fun inheritedRoutesCannotBeRemovedEvenFromAWritableSettingsSnapshot() = runBlocking {
        val profile = ProviderProfile("inherited", null, null, null, null, persistentListOf())
        val settings =
            ProviderSettings(
                persistentListOf(profile),
                17,
                true,
                persistentSetOf("inherited"),
                persistentSetOf("inherited"),
            )
        val operations = Operations(settings)
        try {
            SaveProviderProfileUseCase()(
                operations,
                settings,
                ProviderProfileDraft(
                    17,
                    profile,
                    "inherited",
                    "",
                    ProviderApi.OpenAiCompletions,
                    "",
                    "",
                    emptyList(),
                    emptyMap(),
                    true,
                ),
            )
            fail()
        } catch (error: ProviderOperationException) {
            assertEquals("providers/not-removable", error.code)
            assertNull(operations.mutation)
        }
    }

    @Test
    fun endpointValidationAcceptsHostnamesAndRejectsEmbeddedCredentialsOrQueryData() {
        assertTrue(validProviderBaseUrl("https://例子.test/v1/"))
        assertTrue(validProviderBaseUrl("http://[::1]:8080/v1"))
        assertFalse(validProviderBaseUrl("https://user:password@example.test/v1"))
        assertFalse(validProviderBaseUrl("https://example.test/v1?token=synthetic"))
        assertFalse(validProviderBaseUrl("file:///tmp/provider"))
    }
}

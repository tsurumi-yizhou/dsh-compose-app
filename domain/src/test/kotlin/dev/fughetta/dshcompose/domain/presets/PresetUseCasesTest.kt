package dev.fughetta.dshcompose.domain.presets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PresetUseCasesTest {
    private class Operations : PresetsRepository {
        override val changes = MutableStateFlow(0L)
        override val supportsRemoval = true
        var current = AgentPresetCatalog(emptyList(), emptyList(), false)
        var selected = "unexpected"
        var prompts = 0
        var removed: String? = null

        override suspend fun catalog() = current

        override suspend fun read(id: String): AgentPresetDocument = error("Unused")

        override suspend fun composition(id: String): PresetComposition = error("Unused")

        override suspend fun defaults(): AgentPresetDefaults? = error("Unused")

        override suspend fun setDefault(id: String, previous: AgentPresetDefaults) = error("Unused")

        override suspend fun select(sessionId: String, preset: String) = selected

        override suspend fun render(
            composition: PresetComposition,
            enabled: Set<String>,
            prompt: String,
            complete: Boolean,
        ): String = error("Unused")

        override suspend fun packageFiles(bundle: PresetBundle): PresetPackageFiles =
            error("Unused")

        override suspend fun createSession() = "created-session"

        override suspend fun prompt(sessionId: String, text: String, timeZone: String) {
            prompts++
        }

        override suspend fun remove(name: String): PresetRemovalResult {
            removed = name
            return PresetRemovalResult(false)
        }

        override fun invalidate() {}
    }

    @Test
    fun aRejectedCreatorSelectionPreservesTheCreatedSessionAndDoesNotSendThePrompt() = runBlocking {
        val operations = Operations()
        val result = CreatePresetSessionUseCase()(operations, "Create a preset", "UTC")
        assertEquals("created-session", result.sessionId)
        assertEquals("preset/creator-not-selected", result.error)
        assertEquals(0, operations.prompts)
        operations.selected = "cordis"
        val confirmed = CreatePresetSessionUseCase()(operations, "Create a preset", "UTC")
        assertNull(confirmed.error)
        assertEquals(1, operations.prompts)
    }

    @Test
    fun removalRechecksTheCurrentDefaultAndRequiresAnUnambiguousUserPackage() = runBlocking {
        val owner = PresetInstallation("@local/review", true, false, false, setOf("review"))
        val operations =
            Operations().apply {
                current =
                    AgentPresetCatalog(
                        listOf(AgentPreset("review", true, "Review", null, null, null)),
                        listOf(owner),
                        false,
                    )
            }
        try {
            RemovePresetUseCase()(operations, "review", owner.name)
            fail()
        } catch (error: PresetOperationException) {
            assertEquals("preset/changed", error.code)
            assertNull(operations.removed)
        }
        assertNull(agentPresetOwner("standard", listOf(owner.copy(presetIds = setOf("standard")))))
        assertNull(agentPresetOwner("review", listOf(owner, owner.copy(name = "@local/other"))))
    }
}

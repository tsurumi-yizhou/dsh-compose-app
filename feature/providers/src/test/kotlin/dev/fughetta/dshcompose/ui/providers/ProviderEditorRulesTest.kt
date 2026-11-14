package dev.fughetta.dshcompose.ui.providers

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

/** The extracted editor rules are pure; they are pinned here instead of through the view model. */
class ProviderEditorRulesTest {
    private fun model(id: String = "m") =
        ProviderModelSpec(
            id = id,
            name = "Model",
            contextWindow = 1_000,
            maxTokens = 100,
            input = persistentListOf("text", "image"),
            reasoning =
                ProviderReasoningEfforts(
                    persistentMapOf(
                        "off" to null,
                        "low" to ProviderEffortValue(level = "low"),
                    ),
                ),
        )

    private fun editor() =
        ProviderEditorState(
            revision = 1,
            modelIds = "m",
            importedModels = persistentMapOf("m" to model()),
        )

    @Test
    fun magpiePreservesExplicitProtocolAndApiPath() {
        assertEquals(
            "http://localhost:3425",
            ProviderEditorState(
                    revision = 1,
                    route = "magpie",
                    baseUrl = "localhost:3425",
                )
                .endpointUrl,
        )
        assertEquals(
            "https://magpie.home/api",
            ProviderEditorState(
                    revision = 1,
                    route = "magpie",
                    baseUrl = "https://magpie.home/api",
                )
                .endpointUrl,
        )
        assertEquals(
            "http://10.115.10.117:3425/v1",
            ProviderEditorState(
                    revision = 1,
                    route = "magpie",
                    baseUrl = "10.115.10.117:3425/v1",
                )
                .endpointUrl,
        )
    }

    @Test
    fun openMirrorsModelDefaultsAndTheReasoningLevels() {
        val editor = editor()

        val child = ProviderModelParameters.open(editor, "m", writing = false)!!.modelEditor!!

        assertEquals("Model", child.name)
        assertEquals("1000", child.context)
        assertEquals("100", child.output)
        assertTrue(child.images)
        assertTrue(child.initialImages)
        assertTrue(child.thinking)
        assertEquals(persistentSetOf("low"), child.levels)
        assertTrue(child.offersOff)
    }

    @Test
    fun anUnknownModelOrABusyProviderDoesNotOpenTheEditor() {
        val editor = editor()

        assertNull(ProviderModelParameters.open(editor, "missing", writing = false))
        assertNull(ProviderModelParameters.open(editor, "m", writing = true))
        assertNull(ProviderModelParameters.open(editor.copy(busy = true), "m", writing = false))
        assertNull(ProviderModelParameters.open(null, "m", writing = false))
    }

    @Test
    fun fieldImagesAndThinkingEditsOnlyTouchTheirOwnFields() {
        val child = ProviderModelParameters.open(editor(), "missing", false)
        assertNull(child)

        val opened = ProviderModelParameters.open(editor(), "m", false)!!.modelEditor!!
        assertEquals(
            "renamed",
            ProviderModelParameters.field(opened, ModelParameterField.Name, "renamed").name,
        )
        assertEquals(
            "4096",
            ProviderModelParameters.field(opened, ModelParameterField.ContextWindow, "4096")
                .context,
        )
        assertEquals(
            "512",
            ProviderModelParameters.field(opened, ModelParameterField.MaxTokens, "512").output,
        )
        assertFalse(ProviderModelParameters.images(opened, false).images)
        assertFalse(ProviderModelParameters.thinking(opened, false).thinking)
    }

    @Test
    fun effortTogglesKnownLevelsAndIgnoresUnknownOnes() {
        val opened = ProviderModelParameters.open(editor(), "m", false)!!.modelEditor!!

        assertEquals(
            persistentSetOf("low", "medium"),
            ProviderModelParameters.effort(opened, "medium", providerThinkingLevels).levels,
        )
        assertEquals(
            persistentSetOf<String>(),
            ProviderModelParameters.effort(opened, "low", providerThinkingLevels).levels,
        )
        assertEquals(
            opened,
            ProviderModelParameters.effort(opened, "bogus", providerThinkingLevels),
        )
    }

    @Test
    fun updateOnlyRunsForAnOpenEditorThatIsNotBusy() {
        val opened = ProviderModelParameters.open(editor(), "m", false)!!
        ProviderModelParameters.update(opened, { it.copy(name = "x") })!!

        assertNull(ProviderModelParameters.update(null, { it }))
        assertNull(ProviderModelParameters.update(opened.copy(busy = true), { it }))
        assertNull(ProviderModelParameters.update(opened.copy(modelEditor = null), { it }))
    }

    @Test
    fun catalogContextEditsApplyValidTokenCountsAndCloseClearsTheEditor() {
        val editor =
            ProviderEditorState(
                revision = 1,
                contextEditor = CatalogContextEditorState("m", "Model", "1024"),
            )

        val edited = ProviderCatalogContext.edit(editor, "2048")!!
        assertEquals("2048", edited.contextEditor!!.context)

        val applied = ProviderCatalogContext.apply(edited)!!
        assertNull(applied.contextEditor)
        assertEquals(2048L, applied.editedOverrides["m"]!!.contextWindow)

        val closed = ProviderCatalogContext.close(edited)!!
        assertNull(closed.contextEditor)
        assertNull(ProviderCatalogContext.close(null))
        assertNull(ProviderCatalogContext.edit(editor.copy(busy = true), "1"))
    }
}

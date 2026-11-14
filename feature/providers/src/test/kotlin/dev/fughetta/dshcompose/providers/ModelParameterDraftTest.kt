package dev.fughetta.dshcompose.providers

import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.providers.*
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ModelParameterDraftTest {
    @Test
    fun catalogContextDraftCanRestoreDefaultsAndRevertWithoutChangingRevision() {
        val profile =
            ProviderProfile(
                "gateway",
                "Gateway",
                null,
                null,
                null,
                persistentListOf(),
                modelOverrides =
                    persistentMapOf(
                        "model/a" to ProviderModelOverride.empty().withContextWindow(128000),
                    ),
            )
        val draft =
            ProviderEditorState(
                11,
                profile,
                "gateway",
                "Gateway",
                contextEditor = CatalogContextEditorState("model/a", "Model", "1.5M"),
            )
        val changed = draft.applyCatalogContext()
        assertTrue(changed.changed)
        assertEquals(1500000L, changed.resolvedOverrides().getValue("model/a").contextWindow)
        assertEquals(128000L, profile.modelOverrides.getValue("model/a").contextWindow)
        assertEquals(11L, changed.revision)
        assertNull(changed.contextEditor)
        val reset =
            changed
                .copy(contextEditor = CatalogContextEditorState("model/a", "Model", ""))
                .applyCatalogContext()
        assertTrue(reset.changed)
        assertTrue(reset.resolvedOverrides().isEmpty())
        val reverted =
            reset
                .copy(contextEditor = CatalogContextEditorState("model/a", "Model", "128K"))
                .applyCatalogContext()
        assertFalse(reverted.changed)
        assertTrue(reverted.editedOverrides.isEmpty())
    }

    @Test
    fun parameterOnlyEditsStayInDraftAndRevertingRemovesTheChange() {
        val model =
            ProviderModelSpec.create("model")
                .withParameters(
                    "Original",
                    128000,
                    8192,
                    efforts = setOf("off", "low", "high"),
                )
        val profile =
            ProviderProfile(
                "gateway",
                "Gateway",
                ProviderApi.OpenAiCompletions,
                "https://example.test/v1",
                null,
                persistentListOf(model),
                defaultInput = listOf("text", "image"),
            )
        val child =
            ModelParameterEditorState(
                model,
                "Original",
                "128000",
                "8192",
                true,
                true,
                true,
                persistentSetOf("low", "high"),
                true,
            )
        val draft =
            ProviderEditorState(
                7,
                profile,
                "gateway",
                "Gateway",
                ProviderApi.OpenAiCompletions,
                "https://example.test/v1",
                modelIds = "model",
                modelEditor = child,
            )
        assertFalse(draft.changed)
        assertFalse(draft.applyModelParameters().changed)
        val changed = draft.copy(modelEditor = child.copy(context = "1.5M")).applyModelParameters()
        assertTrue(changed.changed)
        assertEquals(1_500_000L, changed.resolvedModels().single().contextWindow)
        assertTrue(changed.resolvedModels().single().acceptsImages(profile.defaultInput))
        assertEquals(128000L, profile.models.single().contextWindow)
        assertEquals(7L, changed.revision)
        assertNull(changed.modelEditor)
        val reverted = changed.copy(modelEditor = child).applyModelParameters()
        assertFalse(reverted.changed)
        assertTrue(reverted.editedModels.isEmpty())
        val invalid = child.copy(context = "-1")
        assertFalse(invalid.valid)
        assertSame(invalid, draft.copy(modelEditor = invalid).applyModelParameters().modelEditor)
    }
}

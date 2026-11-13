package dev.fughetta.dshcompose.providers

import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.providers.*
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ProviderDraftImportTest {
    @Test
    fun selectedDiscoveryRowsExtendTheDraftWithoutReplacingConfiguredOrManualModels() {
        val originalModel = ProviderModelSpec.create("existing")
        val profile =
            ProviderProfile(
                "gateway",
                "Gateway",
                ProviderApi.OpenAiCompletions,
                "https://example.test/v1",
                "GATEWAY_API_KEY",
                persistentListOf(originalModel),
            )
        fun row(id: String, name: String) =
            DiscoveredModel(id, name, 131072, 8192, persistentListOf("text", "image"))
        val editor =
            ProviderEditorState(
                7,
                profile,
                "gateway",
                "Gateway",
                ProviderApi.OpenAiCompletions,
                "https://example.test/v1",
                "GATEWAY_API_KEY",
                "existing\nmanual",
                discovery =
                    ProviderDiscoveryState(
                        "https://example.test/v1",
                        "gateway",
                        models =
                            persistentListOf(
                                row("existing", "Do not replace"),
                                row("manual", "Do not replace manual"),
                                row("new", "New model"),
                                row("other", "Not selected"),
                            ),
                        selected = persistentSetOf("existing", "manual", "new"),
                    ),
            )
        val imported = editor.withDiscoveredModels()
        assertEquals(listOf("existing", "manual", "new"), imported.models)
        assertEquals(setOf("new"), imported.importedModels.keys)
        assertEquals("New model", imported.importedModels.getValue("new").name)
        assertSame(profile, imported.original)
        assertSame(originalModel, imported.original!!.models.single())
        assertEquals(7L, imported.revision)
        assertNull(imported.discovery)
        assertTrue(imported.changed)
        assertEquals(listOf("existing", "manual"), editor.models)
        assertTrue(editor.importedModels.isEmpty())
    }
}

package dev.fughetta.dshcompose.ui.providers

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ProviderDiscoveryTest {
    private val alpha = DiscoveredModel("alpha", "Alpha", 1_000, 100, null)
    private val beta = DiscoveredModel("beta", "Beta model", 2_000, 200, null)

    private fun editor(
        loading: Boolean = false,
        selected: PersistentSet<String> = persistentSetOf(),
        listed: String = "",
        query: String = "",
    ) =
        ProviderEditorState(
            revision = 1,
            modelIds = listed,
            discovery =
                ProviderDiscoveryState(
                    baseUrl = "",
                    storedRoute = null,
                    loading = loading,
                    models = persistentListOf(alpha, beta),
                    selected = selected,
                    query = query,
                ),
        )

    @Test
    fun theQueryAndTheSelectionToggleInPlace() {
        val state = editor().discovery!!

        assertEquals("al", ProviderDiscovery.filtered(state, "al").query)
        assertEquals(persistentSetOf("alpha"), ProviderDiscovery.toggled(state, "alpha").selected)
        assertEquals(
            persistentSetOf("alpha"),
            ProviderDiscovery.toggled(
                    state.copy(selected = persistentSetOf("alpha", "beta")),
                    "beta",
                )
                .selected,
        )
    }

    @Test
    fun selectAddsTheModelsMatchingTheQueryThatAreNotListedYet() {
        val listed = editor(listed = "alpha", query = "be")

        val next = ProviderDiscovery.selected(listed)!!
        assertEquals(persistentSetOf("beta"), next.discovery!!.selected)

        assertNull(ProviderDiscovery.selected(editor(loading = true)))
        assertNull(ProviderDiscovery.selected(ProviderEditorState(revision = 1)))
    }

    @Test
    fun importAppliesTheSelectionAndClosesTheSheet() {
        val ready = editor(selected = persistentSetOf("alpha"))

        val imported = ProviderDiscovery.imported(ready)!!
        assertEquals("alpha", imported.models.single())
        assertNull(imported.discovery)

        // Nothing selected: the sheet stays as it is.
        assertNull(ProviderDiscovery.imported(editor()))
    }

    @Test
    fun closeOnlyDropsTheSheet() {
        val state = editor(selected = persistentSetOf("alpha"))

        val closed = ProviderDiscovery.close(state)
        assertNull(closed.discovery)
        assertEquals(state.models, closed.models)
    }
}

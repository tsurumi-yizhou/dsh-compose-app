package dev.fughetta.dshcompose.ui.providers

import kotlinx.collections.immutable.*

/**
 * Pure transitions of the provider discovery sheet; the view model keeps its coroutine bookkeeping.
 */
internal object ProviderDiscovery {
    fun filtered(discovery: ProviderDiscoveryState, query: String): ProviderDiscoveryState =
        discovery.copy(query = query)

    fun toggled(discovery: ProviderDiscoveryState, id: String): ProviderDiscoveryState =
        discovery.copy(
            selected =
                if (id in discovery.selected) discovery.selected.removing(id)
                else discovery.selected.adding(id),
        )

    /** Adds every discovered model that matches the query and is not already listed. */
    fun selected(editor: ProviderEditorState): ProviderEditorState? {
        val discovery = editor.discovery?.takeIf { !it.loading } ?: return null
        val added = editor.models.toHashSet()
        val query = discovery.query.trim()
        val ids =
            discovery.models
                .filter {
                    it.id !in added &&
                        (it.id.contains(query, true) || it.name?.contains(query, true) == true)
                }
                .map { it.id }
        return editor.copy(discovery = discovery.copy(selected = discovery.selected.addingAll(ids)))
    }

    /** Applies the selected models and closes the sheet; null when nothing would change. */
    fun imported(editor: ProviderEditorState): ProviderEditorState? {
        val next = editor.withDiscoveredModels()
        return next.takeUnless { it === editor }
    }

    fun close(editor: ProviderEditorState): ProviderEditorState = editor.copy(discovery = null)
}

package dev.fughetta.dshcompose.ui.providers

/**
 * Pure transitions of the catalog context-window editor. The view model keeps the guards that need
 * its own state (writing, connection owner) and publishes whatever these return.
 */
internal object ProviderCatalogContext {
    fun open(
        editor: ProviderEditorState?,
        id: String,
        name: String,
        writing: Boolean,
    ): ProviderEditorState? {
        if (writing) return null
        val live = editor?.takeIf { !it.full && !it.busy && !it.removing } ?: return null
        val known =
            id in live.original?.modelOverrides.orEmpty() || live.catalogModels.any { it.id == id }
        if (!known) return null
        return live.copy(
            contextEditor =
                CatalogContextEditorState(
                    id,
                    name,
                    live.modelOverride(id).contextWindow?.toString().orEmpty(),
                ),
        )
    }

    fun edit(editor: ProviderEditorState?, value: String): ProviderEditorState? {
        val live = editor?.takeIf { !it.busy } ?: return null
        val child = live.contextEditor ?: return null
        return live.copy(contextEditor = child.copy(context = value))
    }

    fun apply(editor: ProviderEditorState?): ProviderEditorState? =
        editor?.takeIf { !it.busy }?.let { it.applyCatalogContext() }

    fun close(editor: ProviderEditorState?): ProviderEditorState? =
        editor?.copy(contextEditor = null)
}

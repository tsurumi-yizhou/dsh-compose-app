package dev.fughetta.dshcompose.ui.providers

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*

/**
 * Pure transitions of the model-parameter editor. The view model only guards (busy, writing, open
 * editor) and publishes the result, which keeps the rules here unit-testable without Android.
 */
internal object ProviderModelParameters {
    /** Opens the editor for [id]; null when it cannot be opened or the provider is busy writing. */
    fun open(editor: ProviderEditorState?, id: String, writing: Boolean): ProviderEditorState? {
        if (writing) return null
        val live =
            editor?.takeIf { !it.busy && !it.removing && it.full && it.discovery == null }
                ?: return null
        val model = live.resolvedModels().firstOrNull { it.id == id } ?: return null
        return live.copy(modelEditor = build(live, model))
    }

    /** Applies [transform] to the open model editor; null when nothing is open. */
    fun update(
        editor: ProviderEditorState?,
        transform: (ModelParameterEditorState) -> ModelParameterEditorState,
    ): ProviderEditorState? {
        val live = editor?.takeIf { !it.busy } ?: return null
        val child = live.modelEditor ?: return null
        return live.copy(modelEditor = transform(child))
    }

    /** Editor state for [model] under the provider editor; mirrors the product defaults. */
    private fun build(
        editor: ProviderEditorState,
        model: ProviderModelSpec,
    ): ModelParameterEditorState {
        val images = model.acceptsImages(editor.original?.defaultInput ?: listOf("text"))
        val levels = model.reasoningLevels.removing("off")
        return ModelParameterEditorState(
            model,
            model.name.orEmpty(),
            model.contextWindow?.toString().orEmpty(),
            model.maxTokens?.toString().orEmpty(),
            images,
            images,
            model.thinks,
            if (levels.isEmpty()) persistentSetOf("low", "medium", "high") else levels,
            !model.thinks || "off" in model.reasoningLevels,
            editor.original?.defaultContextWindow,
            editor.original?.defaultMaxTokens,
        )
    }

    fun field(
        editor: ModelParameterEditorState,
        field: ModelParameterField,
        value: String,
    ): ModelParameterEditorState =
        when (field) {
            ModelParameterField.Name -> editor.copy(name = value)
            ModelParameterField.ContextWindow -> editor.copy(context = value)
            ModelParameterField.MaxTokens -> editor.copy(output = value)
        }

    fun images(editor: ModelParameterEditorState, enabled: Boolean): ModelParameterEditorState =
        editor.copy(images = enabled)

    fun thinking(editor: ModelParameterEditorState, enabled: Boolean): ModelParameterEditorState =
        editor.copy(thinking = enabled)

    /** Adds a known effort when it is off and removes it otherwise; unknown levels are ignored. */
    fun effort(
        editor: ModelParameterEditorState,
        effort: String,
        allowed: List<String>,
    ): ModelParameterEditorState {
        if (effort !in allowed) return editor
        return editor.copy(
            levels =
                if (effort in editor.levels) editor.levels.removing(effort)
                else editor.levels.adding(effort),
        )
    }

    /** Applies the editor into the provider editor when it is valid, otherwise a no-op. */
    fun apply(editor: ProviderEditorState): ProviderEditorState = editor.applyModelParameters()

    fun close(editor: ProviderEditorState): ProviderEditorState = editor.copy(modelEditor = null)
}
